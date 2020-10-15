package se.kth.castor.pankti.generate.serializers;

public interface ISerializer {
    public String serializeObjectToString(Object obj);
    public Object deserializeObjectFromString(String objStr);
}
