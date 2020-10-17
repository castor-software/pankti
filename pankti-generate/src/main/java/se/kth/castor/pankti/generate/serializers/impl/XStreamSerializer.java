package se.kth.castor.pankti.generate.serializers.impl;

import com.thoughtworks.xstream.XStream;
import se.kth.castor.pankti.generate.serializers.ISerializer;

public class XStreamSerializer implements ISerializer {
    @Override
    public String serializeObjectToString(Object obj) {
        XStream xStream = new XStream();
        return xStream.toXML(obj);
    }

    @Override
    public Object deserializeObjectFromString(String objStr, Class klass) {
        XStream xStream = new XStream();
        return klass.cast(xStream.fromXML(objStr));
    }
}
