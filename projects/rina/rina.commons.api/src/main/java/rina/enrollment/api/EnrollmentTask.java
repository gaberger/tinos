package rina.enrollment.api;

import rina.applicationprocess.api.DAFMember;
import rina.cdap.api.CDAPSessionDescriptor;
import rina.cdap.api.message.CDAPMessage;
import rina.ipcprocess.api.IPCProcessComponent;
import rina.ribdaemon.api.RIBObjectNames;

/**
 * The enrollment task manages the members of the DIF. It implements the state machines that are used 
 * to join a DIF or to collaboarate with a remote IPC Process to allow him to join the DIF.
 * @author eduardgrasa
 *
 */
public interface EnrollmentTask extends IPCProcessComponent{
	
	public static final String ENROLLMENT_RIB_OBJECT_NAME = RIBObjectNames.SEPARATOR + RIBObjectNames.DAF + 
		RIBObjectNames.SEPARATOR + RIBObjectNames.MANAGEMENT + RIBObjectNames.SEPARATOR + RIBObjectNames.ENROLLMENT;

	public static final String ENROLLMENT_RIB_OBJECT_CLASS = "enrollment";	
	
	/**
	 * A remote IPC process Connect request has been received
	 * @param cdapMessage
	 * @param cdapSessionDescriptor
	 */
	public void connect(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor);
	
	/**
	 * A remote IPC process Connect response has been received
	 * @param cdapMessage
	 * @param cdapSessionDescriptor
	 */
	public void connectResponse(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor);
	
	/**
	 * A remote IPC process Release request has been received
	 * @param cdapMessage
	 * @param cdapSessionDescriptor
	 */
	public void release(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor);
	
	/**
	 * A remote IPC process Release response has been received
	 * @param cdapMessage
	 * @param cdapSessionDescriptor
	 */
	public void releaseResponse(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor);
	
	/**
	 * Called by the DIFMemberSetRIBObject when a CREATE request for a new member is received
	 * @param cdapMessage
	 * @param cdapSessionDescriptor
	 */
	public void initiateEnrollment(CDAPMessage cdapMessage, CDAPSessionDescriptor cdapSessionDescriptor);
	
	/**
	 * Called by the enrollment state machine when the enrollment request has been completed, either successfully or unsuccessfully
	 * @param candidate the IPC process we were trying to enroll to
	 * @param result the result of the operation (0 = successful, >0 errors)
	 * @param resultReason if result >0, a String explaining what was the problem
	 */
	public void enrollmentCompleted(DAFMember candidate, int result, String resultReason);
	
	/**
	 * Returns the address manager, the object that manages the allocation and usage 
	 * of addresses within a DIF
	 * @return
	 */
	public AddressManager getAddressManager();
}
