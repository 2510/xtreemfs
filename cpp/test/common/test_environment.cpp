/*
 * Copyright (c) 2012 by Michael Berlin, Zuse Institute Berlin
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

#include "common/test_environment.h"

#include "common/test_rpc_server_dir.h"
#include "common/test_rpc_server_mrc.h"
#include "common/test_rpc_server_osd.h"
#include "libxtreemfs/client.h"

using namespace xtreemfs::rpc;

namespace xtreemfs {

TestEnvironment::TestEnvironment()
    : options(), user_credentials() {
  user_credentials.set_username("ClientTest");
  user_credentials.add_groups("ClientTest");

  dir.reset(new TestRPCServerDIR());
  mrc.reset(new TestRPCServerMRC());
  osds.push_back(new TestRPCServerOSD());

  volume_name_ = "test";
}

TestEnvironment::~TestEnvironment() {
  for (size_t i = 0; i < osds.size(); i++) {
    delete osds[i];
  }
}

void TestEnvironment::AddOSDs(int num_of_osds) {
  for (int i = 1; i < num_of_osds; i++) {
    osds.push_back(new TestRPCServerOSD());
  }
}

bool TestEnvironment::Start() {
  if (!dir->Start()) {
    return false;
  }
  if (!mrc->Start()) {
    return false;
  }
  dir->RegisterVolume(volume_name_, mrc->GetAddress());
  for (size_t i = 0; i < osds.size(); i++) {
    if (!osds[i]->Start()) {
      return false;
    }
    mrc->RegisterOSD(osds[i]->GetAddress());
  }
  // TODO(mberlin): Register OSDs at MRC.

  // If the DIR server address was not explicitly overridden, set it to the
  // started test DIR server.
  if (options.service_address.empty()) {
    options.service_address = dir->GetAddress();
  }

  client.reset(Client::CreateClient(options.service_address,
                                    user_credentials,
                                    NULL,  // No SSL options.
                                    options));

  // Start the client (a connection to the DIR service will be setup).
  client->Start();

  return true;
}

void TestEnvironment::Stop() {
  if (client.get()) {
    client->Shutdown();
  }

  if (dir.get()) {
    dir->Stop();
  }
  if (mrc.get()) {
    mrc->Stop();
  }
  for (size_t i = 0; i < osds.size(); i++) {
    osds[i]->Stop();
  }
}

}  // namespace xtreemfs
