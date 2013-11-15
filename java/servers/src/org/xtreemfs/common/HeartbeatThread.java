/*
 * Copyright (c) 2008-2010 by Jan Stender, Bjoern Kolbeck,
 *               Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.common;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.xtreemfs.common.config.ServiceConfig;
import org.xtreemfs.common.util.NetUtils;
import org.xtreemfs.common.uuids.ServiceUUID;
import org.xtreemfs.common.uuids.UUIDResolver;
import org.xtreemfs.dir.DIRClient;
import org.xtreemfs.foundation.LifeCycleThread;
import org.xtreemfs.foundation.TimeSync;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.logging.Logging.Category;
import org.xtreemfs.foundation.pbrpc.Schemes;
import org.xtreemfs.foundation.pbrpc.client.PBRPCException;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.Auth;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.AuthType;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.POSIXErrno;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC.UserCredentials;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMapping;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.AddressMappingSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Configuration;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.Service;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceDataMap;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceSet;
import org.xtreemfs.pbrpc.generatedinterfaces.DIR.ServiceType;
import org.xtreemfs.pbrpc.generatedinterfaces.GlobalTypes.KeyValuePair;

import sun.misc.Signal;
import sun.misc.SignalHandler;

/**
 * A thread that regularly sends a heartbeat signal with fresh service data to the Directory Service.
 */
public class HeartbeatThread extends LifeCycleThread {

    /**
     * An interface that generates service data to be sent to the Directory Service. Each time a heartbeat
     * signal is sent, new service data will be generated by means of invoking <tt>getServiceData()</tt>.
     */
    public interface ServiceDataGenerator {

        public DIR.ServiceSet getServiceData();
    }

    public static final long      UPDATE_INTERVAL           = 60 * 1000;                                      // 60s

    public static final long      CONCURRENT_RETRY_INTERVAL = 5 * 1000;                     // 5s

    private final ServiceUUID           uuid;

    private final ServiceDataGenerator  serviceDataGen;

    private final DIRClient             client;

    private volatile boolean      quit;

    private final ServiceConfig   config;

    private final boolean         advertiseUDPEndpoints;

    private final String          proto;

    private String                advertisedHostName;

    private final UserCredentials uc;

    private static final String   STATIC_ATTR_PREFIX        = "static.";

    public static final String    STATUS_ATTR               = STATIC_ATTR_PREFIX + "status";

    /**
     * If set to true, a RegisterService call (which is the call used by this
     * thread to regularly report at the DIR) will not update the
     * last_updated_s field for the service.
     * Used by tools like xtfs_chstatus.
     */
    public static final String         DO_NOT_SET_LAST_UPDATED   = STATIC_ATTR_PREFIX + "do_not_set_last_updated";

    /**
     * Timestamp when the last heartbeat was sent.
     */
    private long                       lastHeartbeat;

    /** Guards pauseNumberOfWaitingThreads and paused. */
    private final Object               pauseLock;

    /** While >0, the thread will stop its periodic operations. */
    private int                        pauseNumberOfWaitingThreads;

    /** Set to true if the periodic operation is stopped. */
    private boolean                    paused;

    private static Auth                authNone;

    /** Determines if a renewal should take place in the next run of the main loop. **/
    private volatile boolean           addressMappingRenewalPending   = false;

    /** Indicates if a renewal has been triggered. **/
    private volatile boolean           addressMappingRenewalTriggered = false;

    /** Used to sleep until the next heartbeat is scheduled. It can be notified to trigger an instant update **/
    private Object                     updateIntervalMonitor          = new Object();

    static {
        authNone = Auth.newBuilder().setAuthType(AuthType.AUTH_NONE).build();
    }

    public HeartbeatThread(String name, DIRClient client, ServiceUUID uuid, ServiceDataGenerator serviceDataGen,
            ServiceConfig config, boolean advertiseUDPEndpoints) {

        super(name);

        setPriority(Thread.MAX_PRIORITY);

        this.pauseLock = new Object();

        this.client = client;
        this.uuid = uuid;
        this.serviceDataGen = serviceDataGen;
        this.config = config;
        this.advertiseUDPEndpoints = advertiseUDPEndpoints;
        this.uc = UserCredentials.newBuilder().setUsername("hb-thread").addGroups("xtreemfs-services")
                .build();
        if (!config.isUsingSSL()) {
            proto = Schemes.SCHEME_PBRPC;
        } else {
            if (config.isGRIDSSLmode()) {
                proto = Schemes.SCHEME_PBRPCG;
            } else {
                proto = Schemes.SCHEME_PBRPCS;
            }
        }

        if (config.isUsingMultihoming() && config.isUsingRenewalSignal()) {
            enableAddressMappingRenewalSignal();
        }

        this.lastHeartbeat = TimeSync.getGlobalTime();
    }

    @Override
    public void shutdown() {
        try {
            if (client.clientIsAlive()) {
                client.xtreemfs_service_offline(null, authNone, uc, uuid.toString(), 1);
            }
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_WARN, this, "could not set service offline at DIR");
            Logging.logError(Logging.LEVEL_WARN, this, ex);
        }

        this.quit = true;
        this.interrupt();
    }

    public void initialize() throws IOException {
        // initially, ...
        try {

            // ... for each UUID, ...
            for (;;) {
                // catch any ConcurrentModificationException and retry
                try {
                    registerServices(-1);
                    break;
                } catch (PBRPCException ex) {
                    if (ex.getPOSIXErrno() == POSIXErrno.POSIX_ERROR_EAGAIN) {
                        if (Logging.isInfo())
                            Logging.logMessage(Logging.LEVEL_INFO, Category.misc, this,
                                    "concurrent service registration; will try again after %d milliseconds",
                                    CONCURRENT_RETRY_INTERVAL);
                    } else
                        throw ex;
                }
            }

            // ... register the address mapping for the service
            registerAddressMappings();

        } catch (InterruptedException ex) {
        } catch (Exception ex) {
            Logging.logMessage(Logging.LEVEL_ERROR, this,
                    "an error occurred while initially contacting the Directory Service: " + ex);
            throw new IOException("cannot initialize service at XtreemFS DIR: " + ex, ex);
        }

        try {
            this.setServiceConfiguration();
        } catch (Exception e) {
            Logging.logMessage(Logging.LEVEL_ERROR, this,
                    "An error occurred while submitting the service configuration to the DIR service:");
            Logging.logError(Logging.LEVEL_ERROR, this, e);
        }
    }

    @Override
    public void run() {
        try {

            notifyStarted();

            // periodically, ...
            while (!quit) {
                synchronized (pauseLock) {
                    while (pauseNumberOfWaitingThreads > 0) {
                        try {
                            pauseLock.wait();
                        } catch (InterruptedException ex) {
                            quit = true;
                            break;
                        }
                    }

                    paused = false;
                }

                try {
                    // update data on DIR; do not retry, as this is done periodically anyway
                    registerServices(1);
                } catch (PBRPCException ex) {
                    if (ex.getPOSIXErrno() == POSIXErrno.POSIX_ERROR_EAGAIN) {
                        if (Logging.isInfo())
                            Logging.logMessage(Logging.LEVEL_INFO, Category.misc, this,
                                    "concurrent service registration; will try again after %d milliseconds",
                                    UPDATE_INTERVAL);
                    } else
                        Logging.logMessage(Logging.LEVEL_ERROR, this,
                                "An error occurred during the periodic registration at the DIR:");
                    Logging.logError(Logging.LEVEL_ERROR, this, ex);
                } catch (IOException ex) {
                    Logging.logMessage(Logging.LEVEL_ERROR, this, "periodic registration at DIR failed: %s",
                            ex.toString());
                    if (Logging.isDebug())
                        Logging.logError(Logging.LEVEL_DEBUG, this, ex);
                } catch (InterruptedException ex) {
                    quit = true;
                    break;
                }
                
                if (addressMappingRenewalPending) {
                    try {
                        // Reset the flag indicating a renewal has been triggered.
                        addressMappingRenewalTriggered = false;
                        // Try to renew the address mappings.
                        registerAddressMappings();
                        // If the renewal has been successful, the renewal flag will be reset.
                        // If an error occurred, the renewal will be retried on the next regular heartbeat.
                        addressMappingRenewalPending = false;
                        // Renew the networks list available to the UUIDResolver.
                        UUIDResolver.renewNetworks();
                    } catch (IOException ex) {
                        Logging.logMessage(Logging.LEVEL_ERROR, this,
                                "requested renewal of address mappings failed: %s", ex.toString());
                    } catch (InterruptedException ex) {
                        quit = true;
                        break;
                    }
                }

                if (quit) {
                    break;
                }

                synchronized (pauseLock) {
                    paused = true;
                    pauseLock.notifyAll();
                }

                // If no renewal request has been triggered during the loop, this HeartbeatThread can wait for
                // the next regular UPDATE_INTERVAL.
                if (!addressMappingRenewalTriggered) {
                    try {
                        synchronized (updateIntervalMonitor) {
                            updateIntervalMonitor.wait(UPDATE_INTERVAL);
                        }
                    } catch (InterruptedException e) {
                        // ignore
                        // TODO(jdillmann): Revise the exception handling and conditions for termination.
                    }
                }
            }

            notifyStopped();
        } catch (Throwable ex) {
            notifyCrashed(ex);
        }
    }

    private void registerServices(int numRetries) throws IOException, PBRPCException, InterruptedException {

        for (Service reg : serviceDataGen.getServiceData().getServicesList()) {
            // retrieve old DIR entry
            ServiceSet oldSet = numRetries == -1 ? client.xtreemfs_service_get_by_uuid(null, authNone, uc,
                    reg.getUuid()) : client.xtreemfs_service_get_by_uuid(null, authNone, uc, reg.getUuid(),
                    numRetries);
            long currentVersion = 0;
            Service oldService = oldSet.getServicesCount() == 0 ? null : oldSet.getServices(0);

            Map<String, String> staticAttrs = new HashMap();
            if (oldService != null) {
                currentVersion = oldService.getVersion();
                final ServiceDataMap data = oldService.getData();
                for (KeyValuePair pair : data.getDataList()) {
                    if (pair.getKey().startsWith(STATIC_ATTR_PREFIX))
                        staticAttrs.put(pair.getKey(), pair.getValue());
                }
            }

            if (!staticAttrs.containsKey(STATUS_ATTR))
                staticAttrs.put(STATUS_ATTR,
                        Integer.toString(DIR.ServiceStatus.SERVICE_STATUS_AVAIL.getNumber()));

            Service.Builder builder = reg.toBuilder();
            builder.setVersion(currentVersion);
            final ServiceDataMap.Builder data = ServiceDataMap.newBuilder();
            for (Entry<String, String> sAttr : staticAttrs.entrySet()) {
                data.addData(KeyValuePair.newBuilder().setKey(sAttr.getKey()).setValue(sAttr.getValue())
                        .build());
            }

            // If the service to register is a volume, and a volume with the
            // same ID but a different MRC has been registered already, it
            // may be necessary to register the volume's MRC as a replica.
            // In this case, all keys starting with 'mrc' have to be treated
            // separately.
            if (reg.getType() == ServiceType.SERVICE_TYPE_VOLUME && oldService != null
                    && oldService.getUuid().equals(reg.getUuid())) {

                // retrieve the MRC UUID attached to the volume to be
                // registered
                String mrcUUID = null;
                for (KeyValuePair kv : reg.getData().getDataList())
                    if (kv.getKey().equals("mrc")) {
                        mrcUUID = kv.getValue();
                        break;
                    }
                assert (mrcUUID != null);

                // check if the UUID is already contained in the volume's
                // list of MRCs and determine the next vacant key
                int maxMRCNo = 1;
                boolean contained = false;
                for (KeyValuePair kv : oldService.getData().getDataList()) {

                    if (kv.getKey().startsWith("mrc")) {

                        data.addData(kv);

                        if (kv.getValue().equals(mrcUUID))
                            contained = true;

                        if (!kv.getKey().equals("mrc")) {
                            int no = Integer.parseInt(kv.getKey().substring(3));
                            if (no > maxMRCNo)
                                maxMRCNo = no;
                        }
                    }
                }

                // if the UUID is not contained, add it
                if (!contained)
                    data.addData(KeyValuePair.newBuilder().setKey("mrc" + (maxMRCNo + 1)).setValue(mrcUUID));

                // add all other key-value pairs
                for (KeyValuePair kv : reg.getData().getDataList())
                    if (!kv.getKey().startsWith("mrc"))
                        data.addData(kv);

            }

            // in any other case, all data can be updated
            else
                data.addAllData(reg.getData().getDataList());

            builder.setData(data);
            if (numRetries == -1)
                client.xtreemfs_service_register(null, authNone, uc, builder.build());
            else
                client.xtreemfs_service_register(null, authNone, uc, builder.build(), numRetries);

            if (Logging.isDebug()) {
                Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                        "%s successfully updated at Directory Service", uuid);
            }

            // update lastHeartbeat value
            this.lastHeartbeat = TimeSync.getGlobalTime();
        }
    }

    private void setServiceConfiguration() throws IOException, PBRPCException, InterruptedException {
        Configuration conf = client.xtreemfs_configuration_get(null, authNone, uc, uuid.toString());
        long currentVersion = 0;

        currentVersion = conf.getVersion();

        Configuration.Builder confBuilder = Configuration.newBuilder();
        confBuilder.setUuid(uuid.toString()).setVersion(currentVersion);
        for (Map.Entry<String, String> mapEntry : config.toHashMap().entrySet()) {
            confBuilder.addParameter(KeyValuePair.newBuilder().setKey(mapEntry.getKey())
                    .setValue(mapEntry.getValue()).build());
        }

        client.xtreemfs_configuration_set(null, authNone, uc, confBuilder.build());

        if (Logging.isDebug()) {
            Logging.logMessage(Logging.LEVEL_DEBUG, Category.misc, this,
                    "%s successfully send configuration to Directory Service", uuid);
        }
    }

    private void registerAddressMappings() throws InterruptedException, IOException {

        List<AddressMapping.Builder> reachableEndpoints = NetUtils.getReachableEndpoints(config.getPort(), proto);
        
        AddressMapping.Builder advertisedEndpoint = null;

        // Use the configured hostname or listen.address if they are set for the advertised endpoint.
        if (!config.getHostName().isEmpty() || config.getAddress() != null) {
            // remove the leading '/' if necessary
            String host = config.getHostName().isEmpty() ? config.getAddress().getHostName() : config.getHostName();
            if (host.startsWith("/")) {
                host = host.substring(1);
            }

            try {
                // see if we can resolve the hostname
                InetAddress ia = InetAddress.getByName(host);
            } catch (Exception ex) {
                Logging.logMessage(Logging.LEVEL_WARN, this, "WARNING! Could not resolve my "
                        + "hostname (%s) locally! Please make sure that the hostname is set correctly "
                        + "(either on your system or in the service config file). This will lead to "
                        + "problems if clients and other OSDs cannot resolve this service's address!\n", host);
            }

            advertisedEndpoint = AddressMapping.newBuilder().setUuid(uuid.toString()).setVersion(0).setProtocol(proto)
                    .setAddress(host).setPort(config.getPort()).setTtlS(3600)
                    .setUri(proto + "://" + host + ":" + config.getPort());
        }

        // Try to resolve the localHostName and find it in the endpoints to use it as the advertised endpoint if possible.
        if (advertisedEndpoint == null) {
            try {
                InetAddress host = InetAddress.getLocalHost();
                String hostAddress = NetUtils.getHostAddress(host);

                // Try to find the
                for (AddressMapping.Builder mapping : reachableEndpoints) {
                    if (mapping.getAddress().equals(hostAddress)) {
                        advertisedEndpoint = mapping;
                        break;
                    }
                }
            } catch (UnknownHostException e) {
                Logging.logMessage(Logging.LEVEL_WARN, Category.net, this, "Could not resolve the local hostname.");
            }
        }
        
        // Use the first mapping from the reachable endpoints. This will be a global address if one exists.
        if (advertisedEndpoint == null && reachableEndpoints.size() > 0) {
            advertisedEndpoint = reachableEndpoints.get(0);
        }

        // in case no IP address could be found at all, use 127.0.0.1 for local testing.
        if (advertisedEndpoint == null) {
            Logging.logMessage(Logging.LEVEL_WARN, Category.net, this,
                    "Could not find a valid IP address, will use 127.0.0.1 instead.");
            advertisedEndpoint = AddressMapping.newBuilder().setAddress("127.0.0.1").setPort(config.getPort())
                    .setProtocol(proto).setTtlS(3600)
                    .setUri(NetUtils.getURI(proto, InetAddress.getByName("127.0.0.1"), config.getPort()));
        }
       
        // Fetch the latest address mapping version from the Directory Service.
        long version = 0;
        AddressMappingSet ams = client.xtreemfs_address_mappings_get(null, authNone, uc, uuid.toString());

        // Retrieve the version number from the address mapping.
        if (ams.getMappingsCount() > 0) {
            version = ams.getMappings(0).getVersion();
        }
        
        // Set the advertised endpoints version, matching network and uuid.
        advertisedEndpoint.setVersion(version).setMatchNetwork("*").setUuid(uuid.toString());
        advertisedHostName = advertisedEndpoint.getAddress();

        List<AddressMapping.Builder> endpoints = new ArrayList<AddressMapping.Builder>();
        endpoints.add(advertisedEndpoint);
        if (advertiseUDPEndpoints) {
            endpoints.add(NetUtils.cloneMappingForProtocol(advertisedEndpoint, Schemes.SCHEME_PBRPCU));
        }
        
        if (config.isUsingMultihoming()) {
            for (AddressMapping.Builder mapping : reachableEndpoints) {
                // Add all the remaining endpoints not advertised yet.
                if (!advertisedEndpoint.getAddress().equals(mapping.getAddress())) {
                    mapping.setUuid(uuid.toString());
                    endpoints.add(mapping);
                    if (advertiseUDPEndpoints) {
                        endpoints.add(NetUtils.cloneMappingForProtocol(mapping, Schemes.SCHEME_PBRPCU));
                    }
                }
            }
        }

        AddressMappingSet.Builder amsb = AddressMappingSet.newBuilder();
        for (AddressMapping.Builder mapping : endpoints) {
            amsb.addMappings(mapping);
        }

        if (Logging.isInfo()) {
            Logging.logMessage(Logging.LEVEL_INFO, Category.net, this,
                    "Registering the following address mappings for the service:");
            for (AddressMapping mapping : amsb.getMappingsList()) {
                Logging.logMessage(Logging.LEVEL_INFO, Category.net, this, "%s --> %s (%s)", mapping.getUuid(),
                        mapping.getUri(), mapping.getMatchNetwork());
            }
        }

        // Register or update the current address mapping.
        client.xtreemfs_address_mappings_set(null, authNone, uc, amsb.build());
    }

    /**
     * Getter for the timestamp when the last heartbeat was sent.
     * 
     * @return long - timestamp like System.currentTimeMillis() returns it.
     */
    public long getLastHeartbeat() {
        return this.lastHeartbeat;
    }

    /**
     * @return the advertisedHostName
     */
    public String getAdvertisedHostName() {
        return advertisedHostName;
    }

    /**
     * Instructs the HeartbeatThread to pause its current operations. Blocks until it has done so.
     * 
     * @remark Do not forget to call {@link #resumeOperation()} afterward or the thread won't be unpaused.
     * 
     * @throws InterruptedException
     */
    public void pauseOperation() throws InterruptedException {
        synchronized (pauseLock) {
            pauseNumberOfWaitingThreads++;
            while (!paused) {
                try {
                    pauseLock.wait();
                } catch (InterruptedException e) {
                    // In case of a shutdown, abort.
                    pauseNumberOfWaitingThreads--;
                    pauseLock.notifyAll();

                    throw e;
                }
            }
        }
    }

    /**
     * Tells the HeartbeatThread to resume operation.
     */
    public void resumeOperation() {
        synchronized (pauseLock) {
            pauseNumberOfWaitingThreads--;

            pauseLock.notifyAll();
        }
    }

    /**
     * Renew the address mappings immediately (HeartbeatThread will wake up when this is called).
     */
    public void triggerAddressMappingRenewal() {
        addressMappingRenewalPending = true;
        addressMappingRenewalTriggered = true;

        // To make the changes immediate, the thread has to be notified if it is sleeping.
        synchronized (updateIntervalMonitor) {
            updateIntervalMonitor.notifyAll();
        }
    }

    /**
     * Enable a signal handler for USR2 which will trigger the address mapping renewal.
     * 
     * Since it is possible that certain VMs are using the USR2 signal internally, the server should be started with the
     * -XX:+UseAltSigs flag when signal usage is desired.
     * 
     * @throws RuntimeException
     */
    private void enableAddressMappingRenewalSignal() {

        final HeartbeatThread hbt = this;

        // TODO(jdillmann): Test on different VMs and operating systems.
        try {
            Signal.handle(new Signal("USR2"), new SignalHandler() {

                @Override
                public void handle(Signal signal) {
                    // If the HeartbeatThread is still alive, renew the addresses and send them to the DIR.
                    if (hbt != null) {
                        hbt.triggerAddressMappingRenewal();
                    }
                }
            });

        } catch (IllegalArgumentException e) {
            Logging.logMessage(Logging.LEVEL_CRIT, this, "Could not register SignalHandler for USR2.");
            Logging.logError(Logging.LEVEL_CRIT, null, e);

            throw new RuntimeException("Could not register SignalHandler for USR2.", e);
        }
    }
}
