package rina.flowallocator.impl;

import java.net.Socket;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import rina.cdap.api.CDAPSessionDescriptor;
import rina.cdap.api.message.CDAPMessage;
import rina.cdap.api.message.ObjectValue;
import rina.encoding.api.BaseEncoder;
import rina.encoding.api.Encoder;
import rina.flowallocator.api.BaseFlowAllocator;
import rina.flowallocator.api.DirectoryForwardingTable;
import rina.flowallocator.api.FlowAllocatorInstance;
import rina.flowallocator.api.message.Flow;
import rina.flowallocator.impl.ribobjects.DirectoryForwardingTableRIBObject;
import rina.flowallocator.impl.ribobjects.FlowSetRIBObject;
import rina.flowallocator.impl.ribobjects.QoSCubesSetRIBObject;
import rina.flowallocator.impl.tcp.TCPServer;
import rina.flowallocator.impl.validation.AllocateRequestValidator;
import rina.ipcprocess.api.IPCProcess;
import rina.ipcservice.api.APService;
import rina.ipcservice.api.AllocateRequest;
import rina.ipcservice.api.IPCException;
import rina.ribdaemon.api.BaseRIBDaemon;
import rina.ribdaemon.api.RIBDaemon;
import rina.ribdaemon.api.RIBDaemonException;
import rina.ribdaemon.api.RIBObject;
import rina.ribdaemon.api.RIBObjectNames;
import rina.utils.types.Unsigned;

/** 
 * Implements the Flow Allocator
 */
public class FlowAllocatorImpl extends BaseFlowAllocator{
	
	private static final Log log = LogFactory.getLog(FlowAllocatorImpl.class);

	/**
	 * Flow allocator instances, each one associated to a port_id
	 */
	private Map<Integer, FlowAllocatorInstance> flowAllocatorInstances = null;
	
	/**
	 * Validates allocate requests
	 */
	private AllocateRequestValidator allocateRequestValidator = null;
	
	/**
	 * The RIB Daemon
	 */
	private RIBDaemon ribDaemon = null;
	
	/**
	 * The Encoder
	 */
	private Encoder encoder = null;
	
	/**
	 * Shortcut to the directory forwarding table, only for use when reading
	 */
	private DirectoryForwardingTable directoryForwardingTable = null;
	
	/**
	 * Will wait for incoming data connections
	 */
	private TCPServer tcpServer = null;
	
	/**
	 * The thread pool implementation
	 */
	private ExecutorService executorService = null;
	
	/**
	 * The maximum number of worker threads in the Flow Allocator Thread pool
	 * (1 for listening to incoming connections + MAX-1 for reading 
	 * data from sockets)
	 */
	private static int MAXWORKERTHREADS = 10;
	
	/**
	 * The minimum value of a port id not generated by the sockets API
	 * (It has to be greater than 65535 to avoid colliding with the OS 
	 * generated socket number)
	 */
	private static int MIN_PORT_ID_VALUE = 66000;
	
	/**
	 * Used for the generation of port ids
	 */
	private int portIdCounter = MIN_PORT_ID_VALUE;
	
	/**
	 * Stores the list of pending Sockets for which a 
	 * M_CREATE message for a Flow object still has not arrived
	 */
	private Map<Integer, Socket> pendingSockets = null;
	
	public FlowAllocatorImpl(){
		allocateRequestValidator = new AllocateRequestValidator();
		flowAllocatorInstances = new HashMap<Integer, FlowAllocatorInstance>();
		tcpServer = new TCPServer(this);
		executorService = Executors.newFixedThreadPool(MAXWORKERTHREADS);
		executorService.execute(tcpServer);
		pendingSockets = new Hashtable<Integer, Socket>();
	}
	
	@Override
	public void setIPCProcess(IPCProcess ipcProcess){
		super.setIPCProcess(ipcProcess);
		this.ribDaemon = (RIBDaemon) getIPCProcess().getIPCProcessComponent(BaseRIBDaemon.getComponentName());
		this.encoder = (Encoder) getIPCProcess().getIPCProcessComponent(BaseEncoder.getComponentName());
		populateRIB(ipcProcess);
	}
	
	/**
	 * Closes all the sockets and stops
	 */
	@Override
	public void stop(){
		this.tcpServer.setEnd(true);
	}
	
	private void populateRIB(IPCProcess ipcProcess){
		try{
			RIBObject ribObject = new QoSCubesSetRIBObject(ipcProcess);
			ribDaemon.addRIBObject(ribObject);
			ribObject = new FlowSetRIBObject(this, ipcProcess);
			ribDaemon.addRIBObject(ribObject);
			directoryForwardingTable = new DirectoryForwardingTableImpl();
			ribObject = new DirectoryForwardingTableRIBObject(ipcProcess, directoryForwardingTable);
			ribDaemon.addRIBObject(ribObject);
		}catch(RIBDaemonException ex){
			ex.printStackTrace();
			log.error("Could not subscribe to RIB Daemon:" +ex.getMessage());
		}
	}
	
	public DirectoryForwardingTable getDirectoryForwardingTable() {
		return this.directoryForwardingTable;
	}
	
	public Map<Integer, Socket> getPendingSockets(){
		return this.pendingSockets;
	}
	
	/**
	 * The Flow Allocator TCP server notifies that a new TCP 
	 * data flow has been accepted. This operation has to read the remote 
	 * port id and either create a Flow Allocator instance or pass the 
	 * information to an existing one.
	 * @param socket
	 */
	public void newConnectionAccepted(Socket socket){
		byte[] buffer = new byte[2];
		int portId = -1;
		try{
			int dataRead = socket.getInputStream().read(buffer);
			if (dataRead <1){
				//TODO fix this
				throw new Exception();
			}
			Unsigned unsigned = new Unsigned(2);
			unsigned.setValue(buffer);
			portId = new Long(unsigned.getValue()).intValue();
			pendingSockets.put(new Integer(portId), socket);
			notifyFlowAllocatorInstanceIfExists(portId, socket);
			log.debug("The source portId is: "+portId);
		}catch(Exception ex){
			log.error("Accepted incoming TCP connection, but could not read remote portId, closing the socket.");
			try{
				socket.close();
			}catch(Exception e){
				e.printStackTrace();
			}
		}
	}
	
	/**
	 * If a Flow Allocator instance is waiting for this socket, this operation will 
	 * find it and notify about the socket having arrived, so it can procede with the
	 * flow allocation procedure
	 * @param portId
	 * @param socket
	 */
	private void notifyFlowAllocatorInstanceIfExists(int portId, Socket socket){
		Iterator<Integer> iterator = flowAllocatorInstances.keySet().iterator();
		FlowAllocatorInstance flowAllocatorInstance = null;
		
		while(iterator.hasNext()){
			flowAllocatorInstance = flowAllocatorInstances.get(iterator.next());
			if (flowAllocatorInstance.getFlow().getSourcePortId() == portId){
				flowAllocatorInstance.setSocket(socket);
			}
		}
		
		//TODO, if it is not there, add a timer to avoid having the socket being 
		//stored and open forever
	}
	
	/**
	 * Generates a portid for the flow allocation
	 * @return
	 */
	public int generatePortId(){
		if (portIdCounter == Integer.MAX_VALUE){
			portIdCounter = MIN_PORT_ID_VALUE;
		}else{
			portIdCounter++;
		}
		
		while(portIdAlreadyExists(portIdCounter)){
			portIdCounter++;
		}
		
		return portIdCounter;
	}
	
	private boolean portIdAlreadyExists(int candidate){
		Iterator<Integer> iterator = flowAllocatorInstances.keySet().iterator();
		while(iterator.hasNext()){
			if (iterator.next().intValue() == candidate){
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * When an Flow Allocator receives a Create_Request PDU for a Flow object, it consults its local Directory to see if it has an entry.
	 * If there is an entry and the address is this IPC Process, it creates an FAI and passes the Create_request to it.If there is an 
	 * entry and the address is not this IPC Process, it forwards the Create_Request to the IPC Process designated by the address.
	 * @param cdapMessage
	 * @param underlyingPortId
	 */
	public void createFlowRequestMessageReceived(CDAPMessage cdapMessage, int underlyingPortId){
		Flow flow = null;
		long myAddress = 0;
		
		try{
			flow = (Flow) encoder.decode(cdapMessage.getObjValue().getByteval(), Flow.class.toString());
		}catch (Exception ex){
			//Error that has to be fixed, we cannot continue, log it and return
			log.error("Fatal error when deserializing a Flow object. " +ex.getMessage());
			return;
		}
		
		long address = directoryForwardingTable.getAddress(flow.getDestinationNamingInfo());
		
		try{
			myAddress = (Long) ribDaemon.read(null, RIBObjectNames.SEPARATOR + RIBObjectNames.DAF + RIBObjectNames.SEPARATOR + RIBObjectNames.MANAGEMENT + 
					RIBObjectNames.SEPARATOR + RIBObjectNames.NAMING + RIBObjectNames.SEPARATOR + RIBObjectNames.CURRENT_SYNONYM, 0).getObjectValue();
		}catch(RIBDaemonException ex){
			log.error(ex);
			//TODO
		}
		
		if (address == 0){
			//error, the table should have at least returned a default IPC process address to continue looking for the application process
			log.error("The directory forwarding table returned no entries when looking up " + flow.getDestinationNamingInfo().toString());
		}else{
			if (address == myAddress){
				//TODO there is an entry and the address is this IPC Process, create a FAI, extract the Flow object from the CDAP message and
				//call the FAI
				int portId = generatePortId();
				log.debug("The destination application process is reachable through me. Assigning the local portId "+portId+" to the flow allocation.");
				FlowAllocatorInstance flowAllocatorInstance = new FlowAllocatorInstanceImpl(this.getIPCProcess(), null, directoryForwardingTable);
				flowAllocatorInstance.createFlowRequestMessageReceived(flow, portId, cdapMessage, underlyingPortId);
				flowAllocatorInstances.put(new Integer(new Integer(portId)), flowAllocatorInstance);
				//Check if the socket was already established
				Socket socket = pendingSockets.remove(new Integer((int)flow.getSourcePortId()));
				if (socket != null){
					flowAllocatorInstance.setSocket(socket);
				}
				
				//TODO, if socket is null, add a timer to avoid waiting forever. Upon expiration, send a negative M_CREATE response
			}else{
				//The address is not this IPC process, forward the CDAP message to that address increment the hop count of the Flow object
				//extract the flow object from the CDAP message
				flow.setHopCount(flow.getHopCount() - 1);
				if (flow.getHopCount()  <= 0){
					//TODO send negative create Flow response CDAP message to the source IPC process, specifying that the application process
					//could not be found before the hop count expired
				}
				
				try{
					int destinationPortId = Utils.mapAddressToPortId(address, this.getIPCProcess());
					ObjectValue objectValue = new ObjectValue();
					objectValue.setByteval(encoder.encode(flow));
				}catch(Exception ex){
					//Error that has to be fixed, we cannot continue, log it and return
					log.error("Fatal error when serializing a Flow object. " +ex.getMessage());
					return;
				}
			}
		}
	}
	
	/**
	 * When a Create_Response PDU is received the InvokeID is used to deliver to the appropriate FAI.
	 * If the response was negative remove the flow allocator instance from the list of active
	 * flow allocator instances
	 * @param cdapMessage
	 */
	private void createFlowResponseMessageReceived(CDAPMessage cdapMessage){
		//TODO implement this
	}
	
	/**
	 * Forward to the FAI. When it completes, remove the flow allocator instance from the list of active
	 * flow allocator instances
	 * @param cdapMessage
	 */
	private void deleteFlowRequestMessageReceived(CDAPMessage cdapMessage){
		//TODO implement this
	}
	
	/**
	 * Forward to the FAI.When it completes, remove the flow allocator instance from the list of active
	 * flow allocator instances.
	 * @param cdapMessage
	 */
	private void deleteFlowResponseMessageReceived(CDAPMessage cdapMessage){
		//TODO implement this
	}
	
	/**
	 * Validate the request, create a Flow Allocator Instance and forward it the request for further processing
	 * @param allocateRequest
	 * @param applicationProcess
	 * @throws IPCException
	 */
	public void submitAllocateRequest(AllocateRequest allocateRequest, APService applicationProcess){
		log.debug("Received allocate request: "+allocateRequest.toString());
		try {
			allocateRequestValidator.validateAllocateRequest(allocateRequest);
			FlowAllocatorInstance flowAllocatorInstance = new FlowAllocatorInstanceImpl(this.getIPCProcess(), applicationProcess, directoryForwardingTable);
			flowAllocatorInstance.submitAllocateRequest(allocateRequest);
			flowAllocatorInstances.put(new Integer(new Integer(flowAllocatorInstance.getPortId())), flowAllocatorInstance);
		}catch(IPCException ex){
			ex.printStackTrace();
			log.error("Problems processing allocate request: "+ex);
			applicationProcess.deliverAllocateResponse(allocateRequest.getRequestedAPinfo(), 0, -1, ex.getMessage());
		}
	}

	/**
	 * Forward the call to the right FlowAllocator Instance. If the application process 
	 * rejected the flow request, remove the flow allocator instance from the list of 
	 * active flow allocator instances
	 * @param portId
	 * @param success
	 */
	public void submitAllocateResponse(int portId, boolean success, String reason) {
		FlowAllocatorInstance flowAllocatorInstance = flowAllocatorInstances.get(portId);
		if (flowAllocatorInstance == null){
			log.error("Could not find the Flow Allocator Instance associated to the portId "+portId);
			return;
		}
		
		flowAllocatorInstance.submitAllocateResponse(portId, success, reason);
		if (!success){
			flowAllocatorInstances.remove(portId);
		}
	}

	/**
	 * Forward the deallocate request to the Flow Allocator Instance.
	 * @param portId
	 */
	public void submitDeallocate(int portId) {
		FlowAllocatorInstance flowAllocatorInstance = flowAllocatorInstances.get(portId);
		if (flowAllocatorInstance == null){
			log.error("Could not find the Flow Allocator Instance associated to the portId "+portId);
			return;
		}
		
		flowAllocatorInstance.submitDeallocate(portId);
	}
}