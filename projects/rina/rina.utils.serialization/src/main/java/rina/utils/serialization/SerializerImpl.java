package rina.utils.serialization;

import java.util.HashMap;
import java.util.Map;

import rina.serialization.api.Serializer;

/**
 * Implements a serializer that delegates the serialization
 * tasks to different subserializers. A different serializer is registered 
 * by each type of object
 * @author eduardgrasa
 *
 */
public class SerializerImpl implements Serializer{
	
	private Map<String, Serializer> serializers = null;
	
	public SerializerImpl(){
		serializers = new HashMap<String, Serializer>();
	}
	
	/**
	 * Set the class that serializes/unserializes an object class
	 * @param objectClass The object class
	 * @param serializer
	 */
	public void setSerializer(String objectClass, Serializer serializer){
		serializers.put(objectClass, serializer);
	}

	public synchronized Object deserialize(byte[] serializedObject, String objectClass) throws Exception{
		Serializer serializer = serializers.get(objectClass);
		if (serializer == null){
			throw new Exception("No serializers found for class "+objectClass);
		}
		
		return serializer.deserialize(serializedObject, objectClass);
	}

	public synchronized byte[] serialize(Object object) throws Exception {
		Serializer serializer = serializers.get(object.getClass().toString());
		if (serializer == null){
			throw new Exception("No serializers found for class "+object.getClass().toString());
		}
		
		return serializer.serialize(object);
	}

}