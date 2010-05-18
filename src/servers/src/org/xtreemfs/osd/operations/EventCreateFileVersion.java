/*  Copyright (c) 2010 Konrad-Zuse-Zentrum fuer Informationstechnik Berlin.

 This file is part of XtreemFS. XtreemFS is part of XtreemOS, a Linux-based
 Grid Operating System, see <http://www.xtreemos.eu> for more details.
 The XtreemOS project has been developed with the financial support of the
 European Commission's IST program under contract #FP6-033576.

 XtreemFS is free software: you can redistribute it and/or modify it under
 the terms of the GNU General Public License as published by the Free
 Software Foundation, either version 2 of the License, or (at your option)
 any later version.

 XtreemFS is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with XtreemFS. If not, see <http://www.gnu.org/licenses/>.
 */
/*
 * AUTHORS: Jan Stender (ZIB)
 */
package org.xtreemfs.osd.operations;

import org.xtreemfs.foundation.buffer.ReusableBuffer;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.osd.OSDRequest;
import org.xtreemfs.osd.OSDRequestDispatcher;
import org.xtreemfs.osd.stages.StorageStage.CreateFileVersionCallback;
import org.xtreemfs.osd.storage.FileMetadata;

/**
 * 
 * @author bjko
 */
public class EventCreateFileVersion extends OSDOperation {
    
    public EventCreateFileVersion(OSDRequestDispatcher master) {
        super(master);
    }
    
    @Override
    public int getProcedureId() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    @Override
    public void startRequest(OSDRequest rq) {
        throw new UnsupportedOperationException("Not supported yet.");
        
    }
    
    @Override
    public yidl.runtime.Object parseRPCMessage(ReusableBuffer data, OSDRequest rq) throws Exception {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    @Override
    public boolean requiresCapability() {
        throw new UnsupportedOperationException("Not supported yet.");
    }
    
    @Override
    public void startInternalEvent(Object[] args) {
        
        final String fileId = (String) args[0];
        final FileMetadata fi = (FileMetadata) args[1];
        
        master.getStorageStage().createFileVersion(fileId, fi, null, new CreateFileVersionCallback() {
            public void createFileVersionComplete(long fileSize, Exception error) {
                if (error != null) {
                    Logging.logMessage(Logging.LEVEL_ERROR, this, "exception in internal event: %s", error
                            .toString());
                    Logging.logError(Logging.LEVEL_ERROR, this, error);
                }
            }
        });
        
    }
    
}