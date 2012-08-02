package rina.flowallocator.impl;

import java.io.DataOutputStream;
import java.net.Socket;
import java.util.Timer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import rina.cdap.api.CDAPMessageHandler;
import rina.cdap.api.CDAPSessionDescriptor;
import rina.cdap.api.CDAPSessionManager;
import rina.cdap.api.message.CDAPMessage;
import rina.cdap.api.message.ObjectValue;
import rina.configuration.KnownIPCProcessConfiguration;
import rina.configuration.RINAConfiguration;
import rina.delimiting.api.BaseDelimiter;
import rina.delimiting.api.Delimiter;
import rina.efcp.api.BaseDataTransferAE;
import rina.efcp.api.DataTransferAE;
import rina.encoding.api.BaseEncoder;
import rina.encoding.api.Encoder;
import rina.flowallocator.api.FlowAllocator;
import rina.flowallocator.api.FlowAllocatorInstance;
import rina.flowallocator.api.Flow;
import rina.flowallocator.api.Flow.State;
import rina.flowallocator.impl.policies.NewFlowRequestPolicy;
import rina.flowallocator.impl.policies.NewFlowRequestPolicyImpl;
import rina.flowallocator.impl.tcp.TCPSocketReader;
import rina.flowallocator.impl.timertasks.DeleteEFCPStateTimerTask;
import rina.flowallocator.impl.timertasks.LastSDUReceivedTimerTask;
import rina.flowallocator.impl.timertasks.LastSDUSentTimerTask;
import rina.flowallocator.impl.timertasks.SocketClosedTimerTask;
import rina.ipcmanager.api.IPCManager;
import rina.ipcprocess.api.IPCProcess;
import rina.ipcservice.api.APService;
import rina.ipcservice.api.FlowService;
import rina.ipcservice.api.IPCException;
import rina.ipcservice.api.IPCService;
import rina.ribdaemon.api.BaseRIBDaemon;
import rina.ribdaemon.api.RIBDaemon;
import rina.ribdaemon.api.RIBDaemonException;
import rina.ribdaemon.api.RIBObjectNames;

/**
 * The Flow Allocator is the component of the IPC Process that responds to Allocation API invocations 
 * from Application Processes. It creates and monitors a flow and provides any management over its lifetime.
 * Its only service is to network management.
 * @author eduardgrasa
 *
 */
public class FlowAllocatorInstanceImpl implements FlowAllocatorInstance, CDAPMessageHandler{
	
	private static final Log log = LogFactory.getLog(FlowAllocatorInstanceImpl.class);
	public static final long MAXIMUM_PACKET_LIFETIME_IN_MS = 2000L;
	
	/**
	 * The new flow request policy, will translate the allocate request into 
	 * a flow object
	 */
	private NewFlowRequestPolicy newFlowRequestPolicy = null;
	
	/**
	 * The Flow Allocator
	 */
	private FlowAllocator flowAllocator = null;
	
	/**
	 * The portId associated to this Flow Allocator instance
	 */
	private int portId = 0;
	
	/**
	 * The flow object related to this Flow Allocator Instance
	 */
	private Flow flow = null;
	
	/**
	 * The request message;
	 */
	private CDAPMessage requestMessage = null;
	
	/**
	 * The underlying portId to reply to the request message
	 */
	private int underlyingPortId = 0;
	
	/**
	 * The data socket associated to this flow. For the current prototype a 
	 * flow is directly associated to a TCP socket.
	 */
	private Socket socket = null;
	
	/**
	 * Reads the data coming from the TCP connection that supports the flow
	 */
	private TCPSocketReader tcpSocketReader = null;
	
	/**
	 * Controls if this Flow Allocator instance is operative or not
	 */
	private boolean finished = false;
	
	/**
	 * The timer for this Flow Allocator instance
	 */
	private Timer timer = null;
	
	private CDAPSessionManager cdapSessionManager = null;
	
	private IPCProcess ipcProcess = null;
	
	/**
	 * Tells if this flow is local (same system)
	 */
	private boolean local = false;
	
	/**
	 * If this flow is local (same system), this is the portId of the other Flow Allocator Instance
	 */
	private int remotePortId = 0;
	
	/**
	 * The name of the flow object associated to this FlowAllocatorInstance
	 */
	private String objectName = null;
	
	/**
	 * The RIBDaemon of the IPC process
	 */
	private RIBDaemon ribDaemon = null;
	
	/**
	 * The class used to delimit messages
	 */
	private Delimiter delimiter = null;
	
	/**
	 * The class used to encode and decode objects transmitted through CDAP
	 */
	private Encoder encoder = null;
	
	/**
	 * Object used as a lock to synchronize allocate requests and responses
	 */
	private Object allocateLock = null;
	
	/**
	 * The Data Transfer Application Entity
	 */
	private DataTransferAE dataTrasferAE = null;
	
	/**
	 * The callback to the local application
	 */
	private APService applicationCallback = null;
	
	/**
	 * The timer that is set when the last SDU has been sent
	 */
	private LastSDUSentTimerTask lastSDUSentTimerTask = null;
	
	/**
	 * The timer that is set when the last SDU has been sent
	 */
	private LastSDUReceivedTimerTask lastSDUReceivedTimerTask = null;
	
	/**
	 * The IPC Manager
	 */
	private IPCManager ipcManager = null;
	
	public FlowAllocatorInstanceImpl(IPCProcess ipcProcess, FlowAllocator flowAllocator, CDAPSessionManager cdapSessionManager, int portId){
		initialize(ipcProcess, flowAllocator, portId);
		this.timer = new Timer();
		this.cdapSessionManager = cdapSessionManager;
		//TODO initialize the newFlowRequestPolicy
		this.newFlowRequestPolicy = new NewFlowRequestPolicyImpl();
		this.allocateLock = new Object();
		this.dataTrasferAE = (DataTransferAE) ipcProcess.getIPCProcessComponent(BaseDataTransferAE.getComponentName());
		this.ipcManager = ipcProcess.getIPCManager();
		log.debug("Created flow allocator instance to manage the flow identified by portId "+portId);
	}
	
	/**
	 * The flow allocator instance will manage a local flow
	 * @param ipcProcess
	 * @param flowAllocator
	 * @param portId
	 */
	public FlowAllocatorInstanceImpl(IPCProcess ipcProcess, FlowAllocator flowAllocator, int portId){
		initialize(ipcProcess, flowAllocator, portId);
		this.local = true;
		this.dataTrasferAE = (DataTransferAE) ipcProcess.getIPCProcessComponent(BaseDataTransferAE.getComponentName());
		log.debug("Created flow allocator instance to manage the flow identified by portId "+portId);
	}
	
	private void initialize(IPCProcess ipcProcess, FlowAllocator flowAllocator, int portId){
		this.flowAllocator = flowAllocator;
		this.ipcProcess = ipcProcess;
		this.portId = portId;
		this.ribDaemon = (RIBDaemon) ipcProcess.getIPCProcessComponent(BaseRIBDaemon.getComponentName());
		this.delimiter = (Delimiter) ipcProcess.getIPCProcessComponent(BaseDelimiter.getComponentName());
		this.encoder = (Encoder) ipcProcess.getIPCProcessComponent(BaseEncoder.getComponentName());
	}
	
	public Socket getSocket(){
		return this.socket;
	}
	
	public int getPortId(){
		return portId;
	}
	
	public Flow getFlow(){
		return this.flow;
	}
	
	public boolean isFinished(){
		return finished;
	}
	
	/**
	 * Set the application callback for this flow allocation
	 * @param applicationCallback
	 */
	public void setApplicationCallback(APService applicationCallback){
		this.applicationCallback = applicationCallback;
	}

	/**
	 * Generate the flow object, create the local DTP and optionally DTCP instances, generate a CDAP 
	 * M_CREATE request with the flow object and send it to the appropriate IPC process (search the 
	 * directory and the directory forwarding table if needed)
	 * @param flowService
	 * @param applicationCallback the callback to invoke the application for allocateResponse and any other calls
	 * @throws IPCException if there are not enough resources to fulfill the allocate request
	 */
	public void submitAllocateRequest(FlowService flowService, APService applicationCallback) throws IPCException {
		this.applicationCallback = applicationCallback;
		flow = newFlowRequestPolicy.generateFlowObject(flowService);
		log.debug("Generated flow object: "+flow.toString());
		ObjectValue objectValue = null;
		CDAPMessage cdapMessage = null;
		
		//1 Check directory to see to what IPC process the CDAP M_CREATE request has to be delivered
		long destinationAddress = flowAllocator.getDirectoryForwardingTable().getAddress(flowService.getDestinationAPNamingInfo());
		log.debug("The directory forwarding table returned address "+destinationAddress);
		flow.setDestinationAddress(destinationAddress);
		if (destinationAddress == 0){
			throw new IPCException(IPCException.COULD_NOT_FIND_ENTRY_IN_DIRECTORY_FORWARDING_TABLE_CODE, 
					IPCException.COULD_NOT_FIND_ENTRY_IN_DIRECTORY_FORWARDING_TABLE + flowService.getDestinationAPNamingInfo().toString());
		}
		
		//2 Check if the destination address is this IPC process (then invoke degenerated form of IPC)
		long sourceAddress = flowAllocator.getIPCProcess().getAddress().longValue();
		flow.setSourceAddress(sourceAddress);
		String flowName = ""+sourceAddress+"-"+this.portId;
		this.objectName = Flow.FLOW_SET_RIB_OBJECT_NAME + RIBObjectNames.SEPARATOR + flowName;
		if (destinationAddress == sourceAddress){
			local = true;
			this.flowAllocator.receivedLocalFlowRequest(flowService, this.objectName);
			return;
		}
		
		//3 Reserve CEP-ids in EFCP
		int[] cepIds = this.dataTrasferAE.reserveCEPIds(flow.getConnectionIds().size(), portId);
		if (cepIds == null){
			throw new IPCException(IPCException.PROBLEMS_RESERVING_CEP_IDS_CODE, IPCException.PROBLEMS_RESERVING_CEP_IDS);
		}
		for(int i=0; i<cepIds.length; i++){
			flow.getConnectionIds().get(i).setSourceCEPId(cepIds[i]);
		}
		
		try{
			//4 Start the flow allocation sequence by opening a socket to the remote flow allocator
			this.socket = connectToRemoteFlowAllocator(destinationAddress);
			flow.setSourcePortId(portId);
			
			//5 get the portId of the CDAP session to the destination application process name
			int cdapSessionId = Utils.mapAddressToPortId(destinationAddress, flowAllocator.getIPCProcess());

			//6 Encode the flow object and send it to the destination IPC process
			objectValue = new ObjectValue();
			objectValue.setByteval(this.encoder.encode(flow));
			cdapMessage = cdapSessionManager.getCreateObjectRequestMessage(cdapSessionId, null, null, 
					Flow.FLOW_RIB_OBJECT_CLASS, 0, objectName, objectValue, 0, true);
			//Synchronize the actions to send the allocate request and updating state, just in case
			//the response arrived before the request ends
			synchronized(allocateLock){
				this.ribDaemon.sendMessage(cdapMessage, cdapSessionId, this);
				this.underlyingPortId = cdapSessionId;
				this.requestMessage = cdapMessage;
			}
		}catch(Exception ex){
			log.error(ex);
			this.dataTrasferAE.freeCEPIds(portId);
			throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
					IPCException.PROBLEMS_ALLOCATING_FLOW + ex.getMessage());
		}
	}
	
	/**
	 * Establishes a TCP flow to a remote flow allocator and sends the portid as data over the flow
	 * @param destinationAddress
	 * @return
	 */
	private Socket connectToRemoteFlowAllocator(long destinationAddress) throws IPCException{
		log.debug("Trying to connect to IPC Process "+destinationAddress);
		String hostName = null;
		Socket socket = null;
		int port = RINAConfiguration.getInstance().getFlowAllocatorPortNumber(destinationAddress);
		log.debug("Remote Flow Allocator is listening at port "+port);
		
		try{
			KnownIPCProcessConfiguration ipcConf = RINAConfiguration.getInstance().getIPCProcessConfiguration(destinationAddress);
			if (ipcConf == null){
				throw new IPCException(IPCException.UNKNOWN_IPC_PROCESS_CODE, 
						IPCException.UNKNOWN_IPC_PROCESS + destinationAddress);
			}
			hostName = ipcConf.getHostName();
			log.debug("Remote IPC Process contact hostname/IP addresss: "+hostName);
			socket = new Socket(hostName, port);
			log.debug("Socket connected!");
			int tcpRendezVousId = new Long((this.ribDaemon.getIPCProcess().getAddress().longValue() << 16) + portId).intValue();
			DataOutputStream stream = new DataOutputStream(socket.getOutputStream());
			stream.writeInt(tcpRendezVousId);
			stream.flush();
			log.debug("Started a socket to the Flow Allocator at "+hostName+":"+port+". The local socket number is "+socket.getLocalPort());
			return socket;
		}catch(Exception ex){
			throw new IPCException(5, "Problems connecting to remote Flow Allocator. "+ex.getMessage());
		}
	}

	/**
	 * When an FAI is created with a Create_Request(Flow) as input, it will inspect the parameters 
	 * first to determine if the requesting Application (Source_Naming_Info) has access to the requested 
	 * Application (Destination_Naming_Info) by inspecting the Access Control parameter.  If not, a 
	 * negative Create_Response primitive will be returned to the requesting FAI. If it does have access, 
	 * the FAI will determine if the policies proposed are acceptable, invoking the NewFlowRequestPolicy.  
	 * If not, a negative Create_Response is sent.  If they are acceptable, the FAI will invoke a 
	 * Allocate_Request.deliver primitive to notify the requested Application that it has an outstanding 
	 * allocation request.  (If the application is not executing, the FAI will cause the application
	 * to be instantiated.)
	 * @param flow
	 * @param portId
	 * @param invokeId
	 * @param flowObjectName
	 */
	public void createFlowRequestMessageReceived(Flow flow, CDAPMessage requestMessage, int underlyingPortId) {
		log.debug("Create flow request received.\n  "+flow.toString());
		this.flow = flow;
		this.requestMessage = requestMessage;
		this.underlyingPortId = underlyingPortId;
		this.objectName = requestMessage.getObjName();
		flow.setDestinationPortId(portId);
	}
	
	/**
	 * Then this happens both the M_CREATE Flow request and the TCP connection have been established, therefore 
	 * we can continue with the Flow Allocation sequence.
	 * @param socket
	 */
	public void setSocket(Socket socket){
		synchronized(this){
			if (this.socket != null){
				return;
			}
			
			this.socket = socket;
		}
		
		//1 TODO Check if the source application process has access to the destination application process. If not send negative M_CREATE_R 
		//back to the sender IPC process, and housekeeping.
		//Not done in this version, this decision is left to the application 
		//2 TODO If it has, determine if the proposed policies for the flow are acceptable (invoke NewFlowREquestPolicy)
		//Not done in this version, it is assumed that the proposed policies for the flow are acceptable.
		//3 If they are acceptable, the FAI will invoke the Allocate_Request.deliver operation of the destination application process. To do so it will find the
		//IPC Manager and pass it the allocation request.
		FlowService flowService = new FlowService();
		flowService.setSourceAPNamingInfo(flow.getSourceNamingInfo());
		flowService.setDestinationAPNamingInfo(flow.getDestinationNamingInfo());
		flowService.setQoSSpecification(flow.getQosParameters());
		flowService.setPortId(this.portId);
		applicationCallback.deliverAllocateRequest(flowService, (IPCService) ipcProcess);
	}
	
	/**
	 * Called when the Flow Allocator receives a request for a local flow
	 * @param flowService
	 * @param objectName
	 * @throws IPCException
	 */
	public void receivedLocalFlowRequest(FlowService flowService, String objectName) throws IPCException{
		if (!local){
			throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
					IPCException.PROBLEMS_ALLOCATING_FLOW + "This Flow Allocator instance cannot deal with local flows.");
		}
		
		//Check the directory
		this.applicationCallback = flowAllocator.getDirectoryForwardingTable().
				getLocalApplicationCallback(flowService.getDestinationAPNamingInfo());
		if (this.applicationCallback == null){
			throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
					IPCException.PROBLEMS_ALLOCATING_FLOW + "Could not find the callback class to the local application "
					+ flowService.getDestinationAPNamingInfo().getEncodedString());
		}
		
		this.remotePortId = flowService.getPortId();
		this.objectName = objectName;
		flowService.setPortId(portId);
		this.applicationCallback.deliverAllocateRequest(flowService, (IPCService) ipcProcess);
	}

	/**
	 * When the FAI gets a Allocate_Response from the destination application, it formulates a Create_Response 
	 * on the flow object requested.If the response was positive, the FAI will cause DTP and if required DTCP 
	 * instances to be created to support this allocation. A positive Create_Response Flow is sent to the 
	 * requesting FAI with the connection-endpoint-id and other information provided by the destination FAI. 
	 * The Create_Response is sent to requesting FAI with the necessary information reflecting the existing flow, 
	 * or an indication as to why the flow was refused.  
	 * If the response was negative, the FAI does any necessary housekeeping and terminates.
	 * @param portId
	 * @param reason
	 * @throws IPCException
	 */
	public void submitAllocateResponse(boolean success, String reason, APService applicationCallback) throws IPCException{
		CDAPMessage cdapMessage = null;
		this.applicationCallback = applicationCallback;
		
		//If the IPC flow is between local applications
		if (local){
			this.dataTrasferAE.createLocalConnectionAndBindToPortId(this.portId, this.remotePortId, applicationCallback);
			this.flowAllocator.receivedLocalFlowResponse(this.remotePortId, this.portId, success, reason);
			this.flow.setState(State.ALLOCATED);
			if (success){
				try{
					this.ribDaemon.create(Flow.FLOW_RIB_OBJECT_CLASS, this.objectName, this);
					this.ipcManager.addFlowQueues(portId, RINAConfiguration.getInstance().getLocalConfiguration().getLengthOfFlowQueues());
					this.dataTrasferAE.subscribeToFlow(portId);
				}catch(Exception ex){
					ex.printStackTrace();
				}
			}
			return;
		}
		
		//If the flow is between a local and a remote application
		if (success){
			//1 Reserve CEP ids
			int[] cepIds = this.dataTrasferAE.reserveCEPIds(flow.getConnectionIds().size(), portId);
			if (cepIds == null){
				//Create CDAP response message
				try{
					cdapMessage = cdapSessionManager.getCreateObjectResponseMessage(underlyingPortId, null, requestMessage.getObjClass(), 
							0, requestMessage.getObjName(), null, -1, IPCException.PROBLEMS_RESERVING_CEP_IDS, requestMessage.getInvokeID());
					this.ribDaemon.sendMessage(cdapMessage, underlyingPortId, null);
				}catch(Exception ex){
					log.error(ex);
					throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
							IPCException.PROBLEMS_ALLOCATING_FLOW + ex.getMessage());
				}
				
				throw new IPCException(IPCException.PROBLEMS_RESERVING_CEP_IDS_CODE, 
						IPCException.PROBLEMS_RESERVING_CEP_IDS);
			}
			for(int i=0; i<cepIds.length; i++){
				flow.getConnectionIds().get(i).setDestinationCEPId(cepIds[i]);
			}
			
			//2 Create DTP and DTCP instances, and bind it to the port Id
			this.dataTrasferAE.createConnectionAndBindToPortId(flow, socket, this.applicationCallback);
			
			//3 Update Flow state
			this.flow.setState(State.ALLOCATED);
			
			//4 Add the flow queue
			this.ipcManager.addFlowQueues(portId, RINAConfiguration.getInstance().getLocalConfiguration().getLengthOfFlowQueues());
			this.dataTrasferAE.subscribeToFlow(portId);
			
			//5 Create CDAP response message
			try{
				ObjectValue objectValue = new ObjectValue();
				objectValue.setByteval(this.encoder.encode(flow));
				cdapMessage = cdapSessionManager.getCreateObjectResponseMessage(underlyingPortId, null, requestMessage.getObjClass(), 
						0, requestMessage.getObjName(), objectValue, 0, null, requestMessage.getInvokeID());
				this.ribDaemon.sendMessage(cdapMessage, underlyingPortId, null);
				this.ribDaemon.create(requestMessage.getObjClass(), requestMessage.getObjName(), this);
				tcpSocketReader = new TCPSocketReader(socket, this.delimiter, this.dataTrasferAE, this);
				this.ipcProcess.execute(tcpSocketReader);
			}catch(Exception ex){
				log.error(ex);
				this.dataTrasferAE.deleteConnection(flow.getConnectionIds().get(0));
				this.dataTrasferAE.freeCEPIds(this.portId);
				throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
						IPCException.PROBLEMS_ALLOCATING_FLOW + ex.getMessage());
			}
		}else{
			//Create CDAP response message
			try{
				cdapMessage = cdapSessionManager.getCreateObjectResponseMessage(underlyingPortId, null, requestMessage.getObjClass(), 
						0, requestMessage.getObjName(), null, -1, reason, requestMessage.getInvokeID());
				this.ribDaemon.sendMessage(cdapMessage, underlyingPortId, null);
			}catch(Exception ex){
				log.error(ex);
				throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
						IPCException.PROBLEMS_ALLOCATING_FLOW + ex.getMessage());
			}
		}
	}

	/**
	 * When a deallocate primitive is invoked, it is passed to the FAI responsible for that port-id.  
	 * The FAI sends an M_DELETE request CDAP PDU on the Flow object referencing the destination port-id, deletes the local 
	 * binding between the Application and the DTP-instance and waits for a response.  (Note that 
	 * the DTP and DTCP if it exists will be deleted automatically after 2MPL)
	 * @throws IPCException
	 */
	public void submitDeallocate() throws IPCException{
		if (local){
			//1 Notify the flow allocator
			this.flowAllocator.receivedDeallocateLocalFlowRequest(this.remotePortId);
			//2 Housekeeping and remove state from RIB
			destroyFlowAllocatorInstance(this.objectName);
		}else{
			try{
				//1 Send 0-byte SDU to indicate that all the data has already been sent
				this.ipcManager.getIncomingFlowQueue(portId).writeDataToQueue(new byte[0]);

				//2 Update flow state
				this.flow.setState(State.LAST_SDU_SENT);
				
				//3 Set timer
				lastSDUSentTimerTask = new LastSDUSentTimerTask(this);
				timer.schedule(lastSDUSentTimerTask, LastSDUSentTimerTask.DELAY);
			}catch(Exception ex){
				log.error(ex);
				throw new IPCException(IPCException.PROBLEMS_DEALLOCATING_FLOW_CODE, 
						IPCException.PROBLEMS_DEALLOCATING_FLOW + ex.getMessage());
			}
		}
	}
	
	/**
	 * The last SDU for this flow has been received. Close the socket, update the 
	 * flow state and set a timer. When the timer expires, if the M_DELETE flow 
	 * request has not been received, invoke deliverDeallocate to the local app and 
	 * cleanup flow related resources.
	 */
	public void lastSDUReceived(){
		this.flow.setState(State.LAST_SDU_DELIVERED);
		
		try{
			this.socket.close();
		}catch(Exception ex){
		}
		
		lastSDUReceivedTimerTask = new LastSDUReceivedTimerTask(this);
		timer.schedule(lastSDUReceivedTimerTask, LastSDUReceivedTimerTask.DELAY);
	}

	/**
	 * When this PDU is received by the FAI with this port-id, the FAI invokes a Deallocate.deliver to notify the local Application, 
	 * deletes the binding between the Application and the local DTP-instance, and sends a Delete_Response indicating the result.
	 * @param cdapMessage
	 * @param underlyingPortId
	 */
	public void deleteFlowRequestMessageReceived(CDAPMessage cdapMessage, int underlyingPortId){
		//1 Cancel timer
		this.lastSDUReceivedTimerTask.cancel();
		
		//2 Notify application
		this.applicationCallback.deliverDeallocate(portId);
		
		//3 HouseKeeping and remove state from RIB
		destroyFlowAllocatorInstance(this.objectName);
	}
	
	/**
	 * Request to deallocate a local flow
	 * @throws IPCException
	 */
	public void receivedDeallocateLocalFlowRequest() throws IPCException{
		//1 Notify application
		this.applicationCallback.deliverDeallocate(portId);
		
		//2 HouseKeeping and remove state from RIB
		destroyFlowAllocatorInstance(this.objectName);
	}
	
	/**
	 * If the response to the allocate request is negative 
	 * the Allocation invokes the AllocateRetryPolicy. If the AllocateRetryPolicy returns a positive result, a new Create_Flow Request 
	 * is sent and the CreateFlowTimer is reset.  Otherwise, if the AllocateRetryPolicy returns a negative result or the MaxCreateRetries has been exceeded, 
	 * an Allocate_Request.deliver primitive to notify the Application that the flow could not be created. (If the reason was 
	 * “Application Not Found,” the primitive will be delivered to the Inter-DIF Directory to search elsewhere.The FAI deletes the DTP and DTCP instances 
	 * it created and does any other housekeeping necessary, before terminating.  If the response is positive, it completes the binding of the DTP-instance 
	 * with this connection-endpoint-id to the requesting Application and invokes a Allocate_Request.submit primitive to notify the requesting Application 
	 * that its allocation request has been satisfied.
	 * @param CDAPMessage
	 * @param CDAPSessionDescriptor
	 */
	public void createResponse(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor) throws RIBDaemonException {
		//Synchronize here, just in case the allocate response arrived before finishing processing the allocation request
		synchronized(allocateLock){
			if (!cdapMessage.getObjName().equals(requestMessage.getObjName())){
				log.error("Expected create flow response message for flow "+requestMessage.getObjName()+
						", but received create flow response message for flow "+cdapMessage.getObjName());
				//TODO, what to do?
				return;
			}
		}
		
		if (cdapMessage.getResult() != 0){
			log.debug("Unsuccessful create flow response message received for flow "+cdapMessage.getObjName());
			destroyFlowAllocatorInstance(objectName);
			this.applicationCallback.deliverAllocateResponse(portId, cdapMessage.getResult(), cdapMessage.getResultReason());
			return;
		}

		try{
			//Update the destination CEP-id of our flow object
			if (cdapMessage.getObjValue() != null){
				Flow receivedFlow = (Flow) this.encoder.decode(cdapMessage.getObjValue().getByteval(), Flow.class);
				for(int i=0; i<receivedFlow.getConnectionIds().size(); i++){
					this.flow.getConnectionIds().get(i).setDestinationCEPId(
							receivedFlow.getConnectionIds().get(i).getDestinationCEPId());
				}
			}
			
			//5 Create an instance of DTP/DTCP and bind it to the port Id
			this.dataTrasferAE.createConnectionAndBindToPortId(flow, socket, this.applicationCallback);
			
			//6 Create the incoming and outgoing data queues
			this.ipcManager.addFlowQueues(portId, RINAConfiguration.getInstance().getLocalConfiguration().getLengthOfFlowQueues());
			this.dataTrasferAE.subscribeToFlow(portId);
			
			//7 Create the Flow object in the RIB, start a socket reader and deliver the response to the application
			log.debug("Successfull create flow message response received for flow "+cdapMessage.getObjName()+".\n "+this.flow.toString());
			this.ribDaemon.create(Flow.FLOW_RIB_OBJECT_CLASS, objectName, this);
			this.tcpSocketReader = new TCPSocketReader(socket, this.delimiter, this.dataTrasferAE, this);
			this.ipcProcess.execute(tcpSocketReader);
			this.applicationCallback.deliverAllocateResponse(portId, 0, null);
		}catch(Exception ex){
			ex.printStackTrace();
		}
	}
	
	/**
	 * Called when the Flow Allocator receives a response to a request for a local flow
	 * @param remotePortId
	 * @param result
	 * @param resultReason
	 * @throws IPCException
	 */
	public void receivedLocalFlowResponse(int remotePortId, boolean result, String resultReason) throws IPCException{
		if (!local){
			throw new IPCException(IPCException.PROBLEMS_ALLOCATING_FLOW_CODE, 
					IPCException.PROBLEMS_ALLOCATING_FLOW + "This Flow Allocator instance cannot deal with local flows.");
		}
		
		if (result){
			try{
				this.dataTrasferAE.createLocalConnectionAndBindToPortId(this.portId, remotePortId, this.applicationCallback);
				this.remotePortId = remotePortId;
				this.applicationCallback.deliverAllocateResponse(portId, 0, null);
				this.ribDaemon.create(Flow.FLOW_RIB_OBJECT_CLASS, objectName, this);
				this.ipcManager.addFlowQueues(portId, RINAConfiguration.getInstance().getLocalConfiguration().getLengthOfFlowQueues());
				this.dataTrasferAE.subscribeToFlow(portId);
			}catch(Exception ex){
				ex.printStackTrace();
			}
		}else{
			this.applicationCallback.deliverAllocateResponse(portId, -1, resultReason);
		}
	}
	
	public void destroyFlowAllocatorInstance(String flowObjectName){
		//1 Close the socket if it is still open
		if (this.socket != null){
			try{
				this.socket.close();
			}catch(Exception ex){
				log.error(ex.getMessage());
				ex.printStackTrace();
			}
		}
		
		//2 Delete the object from the RIB
		try{
			this.ribDaemon.delete(Flow.FLOW_RIB_OBJECT_CLASS, flowObjectName);
		}catch(Exception ex){
			log.error(ex.getMessage());
			ex.printStackTrace();
		}
		
		//3 Cleanup the remaining state (FAI and EFCP) after 2 MPL
		DeleteEFCPStateTimerTask deleteEFCPStateTimerTask = new DeleteEFCPStateTimerTask(this.timer, 
				this.flowAllocator, this.ipcManager, this.portId, this.flow, this.dataTrasferAE);
		timer.schedule(deleteEFCPStateTimerTask, 2*MAXIMUM_PACKET_LIFETIME_IN_MS);
	}

	public void deleteResponse(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor) throws RIBDaemonException{
	}
	
	/**
	 * When the TCP Socket Reader detects that the socket is closed, 
	 * it will notify the Flow Allocator instance
	 */
	public void socketClosed(){
		synchronized(this.flow){
			switch (this.flow.getState()){
			case DEALLOCATED:
				//Do nothing
				break;
			case LAST_SDU_DELIVERED:
				//Do nothing
				break;
			case LAST_SDU_SENT:
				//1 Cancel timer
				lastSDUSentTimerTask.cancel();
				
				//2 If the socket is not closed, close the socket
				if (!this.socket.isClosed()){
					try{
						this.socket.close();
					}catch(Exception ex){
					}
				}

				//3 Send M_DELETE
				try{
					ObjectValue objectValue = new ObjectValue();
					objectValue.setByteval(this.encoder.encode(flow));
					requestMessage = cdapSessionManager.getDeleteObjectRequestMessage(
							underlyingPortId, null, null, "flow", 0, requestMessage.getObjName(), null, 0, false); 
					this.ribDaemon.sendMessage(requestMessage, underlyingPortId, null);
				}catch(Exception ex){
					log.error("Problems sending M_DELETE flow request");
				}

				//4 Housekeeping and remove state from RIB
				destroyFlowAllocatorInstance(requestMessage.getObjName());
				this.flow.setState(State.DEALLOCATED);
				break;
			default:
				//Notify local app
				//Clean resources
				this.flow.setState(State.DEALLOCATED);
			}
		}
		
		SocketClosedTimerTask task = new SocketClosedTimerTask(this, requestMessage.getObjName());
		timer.schedule(task, SocketClosedTimerTask.DELAY);
	}
	
	public void cancelReadResponse(CDAPMessage arg0, CDAPSessionDescriptor arg1) throws RIBDaemonException {
		// TODO Auto-generated method stub
	}

	public void readResponse(CDAPMessage arg0, CDAPSessionDescriptor arg1)
			throws RIBDaemonException {
		// TODO Auto-generated method stub
		
	}

	public void startResponse(CDAPMessage arg0, CDAPSessionDescriptor arg1)
			throws RIBDaemonException {
		// TODO Auto-generated method stub
		
	}

	public void stopResponse(CDAPMessage arg0, CDAPSessionDescriptor arg1)
			throws RIBDaemonException {
		// TODO Auto-generated method stub
		
	}

	public void writeResponse(CDAPMessage arg0, CDAPSessionDescriptor arg1)
			throws RIBDaemonException {
		// TODO Auto-generated method stub
		
	}
}
