package rina.rmt.api;

import rina.ipcprocess.api.BaseIPCProcessComponent;

/**
 * Provides the component name for the RMT
 * @author eduardgrasa
 *
 */
public abstract class BaseRMT extends BaseIPCProcessComponent implements RMT {
	
	public static final String RMT_PORT_PROPERTY = "rina.rmt.port";
	public static final int DEFAULT_PORT = 32769;

	public static final String getComponentName(){
		return RMT.class.getName();
	}
	
	public String getName() {
		return BaseRMT.getComponentName();
	}

}
