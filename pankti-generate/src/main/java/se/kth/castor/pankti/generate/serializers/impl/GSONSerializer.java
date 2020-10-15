package se.kth.castor.pankti.generate.serializers.impl;

import com.google.gson.Gson;
import se.kth.castor.pankti.generate.serializers.ISerializer;

public class GSONSerializer implements ISerializer {
    private Gson gson = new Gson();

    @Override
    public String serializeObjectToString(Object obj) {
        return gson.toJson(obj);
    }

    @Override
    public Object deserializeObjectFromString(String objStr) {
        return null;
    }
}