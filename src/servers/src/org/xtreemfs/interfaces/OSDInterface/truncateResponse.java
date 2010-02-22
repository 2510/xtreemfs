package org.xtreemfs.interfaces.OSDInterface;

import java.io.StringWriter;
import org.xtreemfs.*;
import org.xtreemfs.common.buffer.ReusableBuffer;
import org.xtreemfs.interfaces.*;
import org.xtreemfs.interfaces.utils.*;
import yidl.runtime.Marshaller;
import yidl.runtime.PrettyPrinter;
import yidl.runtime.Struct;
import yidl.runtime.Unmarshaller;




public class truncateResponse extends org.xtreemfs.interfaces.utils.Response
{
    public static final int TAG = 2010022526;

    public truncateResponse() { osd_write_response = new OSDWriteResponse();  }
    public truncateResponse( OSDWriteResponse osd_write_response ) { this.osd_write_response = osd_write_response; }

    public OSDWriteResponse getOsd_write_response() { return osd_write_response; }
    public void setOsd_write_response( OSDWriteResponse osd_write_response ) { this.osd_write_response = osd_write_response; }

    // java.lang.Object
    public String toString() 
    { 
        StringWriter string_writer = new StringWriter();
        string_writer.append(this.getClass().getCanonicalName());
        string_writer.append(" ");
        PrettyPrinter pretty_printer = new PrettyPrinter( string_writer );
        pretty_printer.writeStruct( "", this );
        return string_writer.toString();
    }


    // java.io.Serializable
    public static final long serialVersionUID = 2010022526;

    // yidl.runtime.Object
    public int getTag() { return 2010022526; }
    public String getTypeName() { return "org::xtreemfs::interfaces::OSDInterface::truncateResponse"; }

    public int getXDRSize()
    {
        int my_size = 0;
        my_size += osd_write_response.getXDRSize(); // osd_write_response
        return my_size;
    }

    public void marshal( Marshaller marshaller )
    {
        marshaller.writeStruct( "osd_write_response", osd_write_response );
    }

    public void unmarshal( Unmarshaller unmarshaller )
    {
        osd_write_response = new OSDWriteResponse(); unmarshaller.readStruct( "osd_write_response", osd_write_response );
    }

    

    private OSDWriteResponse osd_write_response;

}

