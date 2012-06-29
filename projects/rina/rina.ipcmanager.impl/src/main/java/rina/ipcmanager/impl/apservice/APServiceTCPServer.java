package rina.ipcmanager.impl.apservice;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import rina.delimiting.api.DelimiterFactory;
import rina.idd.api.InterDIFDirectory;
import rina.ipcmanager.api.IPCManager;
import rina.ipcprocess.api.IPCProcessFactory;

/**
 * Listens to local connections from applications that want to use the RINA services.
 * In reality the calls will come from the API libraries (Native RINA or faux sockets).
 * @author eduardgrasa
 *
 */
public class APServiceTCPServer implements Runnable{
	
private static final Log log = LogFactory.getLog(APServiceTCPServer.class);
	
	public static final int DEFAULT_PORT = 32771;
	
	/**
	 * Controls when the server will finish the execution
	 */
	private boolean end = false;
	
	/**
	 * The TCP port to listen for incoming connections
	 */
	private int port = 0;
	
	/**
	 * The server socket that listens for incoming connections
	 */
	private ServerSocket serverSocket = null;
	
	/**
	 * The IPC Manager
	 */
	private IPCManager ipcManager = null;
	
	/**
	 * The IPC Process Factory
	 */
	private IPCProcessFactory ipcProcessFactory = null;
	
	/**
	 * The Inter DIF Directory
	 */
	private InterDIFDirectory interDIFDirectory = null;
	
	public APServiceTCPServer(IPCManager ipcManager){
		this(ipcManager, DEFAULT_PORT);
	}
	
	public APServiceTCPServer(IPCManager ipcManager, int port){
		this.ipcManager = ipcManager;
		this.port = port;
	}
	
	public void setInterDIFDirectory(InterDIFDirectory interDIFDirectory){
		this.interDIFDirectory = interDIFDirectory;
	}
	
	public void setIPCProcessFactory(IPCProcessFactory ipcProcessFactory){
		this.ipcProcessFactory = ipcProcessFactory;
	}
	
	public void setEnd(boolean end){
		this.end = end;
		if (end){
			try{
				this.serverSocket.close();
			}catch(IOException ex){
				log.error(ex.getMessage());
			}
		}
	}

	public void run() {
		try{
			serverSocket = new ServerSocket(port);
			log.info("IPC Manager waiting for incoming TCP connections from applications on port "+port);
			while (!end){
				Socket socket = serverSocket.accept();
				String address = socket.getInetAddress().getHostAddress();
				String hostname = socket.getInetAddress().getHostName();
				
				//Accept only local connections
				if (!address.equals("127.0.0.1") && !hostname.equals("localhost")){
					log.info("Connection attempt from "+address+" blocked");
					socket.close();
					continue;
				}
				
				log.info("Got a new request from "+socket.getInetAddress().getHostAddress() + 
						". Local port: "+socket.getLocalPort()+"; Remote port: "+socket.getPort());
				
				//Call the AP Service
				this.newConnectionAccepted(socket);
			}
		}catch(IOException e){
			log.error(e.getMessage());
		}
	}
	
	private void newConnectionAccepted(Socket socket){
		APServiceImpl apService = new APServiceImpl(this.ipcManager);
		apService.setInterDIFDirectory(interDIFDirectory);
		apService.setIPCProcessFactory(ipcProcessFactory);
		TCPSocketReader socketReader = new TCPSocketReader(socket, ipcProcessFactory.getDelimiterFactory().createDelimiter(DelimiterFactory.DIF),
				ipcProcessFactory.getEncoderFactory().createEncoderInstance(), ipcProcessFactory.getCDAPSessionManagerFactory().createCDAPSessionManager(), 
				apService);
		ipcManager.execute(socketReader);
	}

}
