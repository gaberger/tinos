/*
 * $Id: UDPProtocol.java 4214 2008-06-08 04:37:59Z crawley $
 *
 * JNode.org
 * Copyright (C) 2003-2006 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
/*
 * 2008 - 2010 (c) Waterford Institute of Technology
 *		   TSSG, EU ICT 4WARD
 *
 * 2010 (c) Pouzin Society
 *   - Forked from EU ICT 4WARD Open Source Distribution.
 *   - Organisation Strings updated to reflect fork.
 *
 *
 * Author        : pphelan(at)tssg.org
 *
 * Modifications : Changes to port JNode code base to OSGi platform.
 *                 - log4j -> apache commons.
 *                 - java.net -> jnode.net
 *                 - multicast support.
 *
 */
package org.jnode.net.ipv4.udp;

import jnode.net.DatagramSocketImplFactory;
import jnode.net.InetAddress;
import jnode.net.SocketImplFactory;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Random;

import org.apache.commons.logging.*;
import org.jnode.driver.net.NetworkException;
import org.jnode.net.SocketBuffer;
import org.jnode.net.ipv4.IPv4Constants;
import org.jnode.net.ipv4.IPv4Header;
import org.jnode.net.ipv4.IPv4Protocol;
import org.jnode.net.ipv4.IPv4Service;
import org.jnode.net.ipv4.icmp.ICMPUtils;
import org.jnode.util.Statistics;

/**
 * @author epr
 * @author Martin Husted Hartvig (hagar@jnode.org)
 */
public class UDPProtocol implements IPv4Protocol, IPv4Constants {

    /**
     * My logger
     */
    private static final Log log = LogFactory.getLog(UDPProtocol.class);

    /**
     * The underlying IP service
     */
    private final IPv4Service ipService;

    /**
     * Socket bindings (lport, socket)
     */
    private final HashMap<Integer, UDPDatagramSocketImpl> sockets =
            new HashMap<Integer, UDPDatagramSocketImpl>();

    /**
     * DatagramSocketImplFactor instance
     */
    private final UDPDatagramSocketImplFactory dsiFactory;

    /**
     * My statistics
     */
    private final UDPStatistics stat = new UDPStatistics();

    /**
     * ICMP utility
     */
    private final ICMPUtils icmp;

    /**
     * for random listener ports
     */
    private final Integer zero = new Integer(0);
    private final Random random = new Random();

    private final int startRandom = 1024;
    private final int stopRandom = (65535 - startRandom);

    /**
     * Create a new instance
     * 
     * @param ipService
     */
    public UDPProtocol(IPv4Service ipService) throws NetworkException {
        this.ipService = ipService;
        this.icmp = new ICMPUtils(ipService);
        this.dsiFactory = new UDPDatagramSocketImplFactory(this);
        ipService.registerProtocol(this);
        log.info("UDPProtocol Started");
    }

    /**
     * @see org.jnode.net.ipv4.IPv4Protocol#getName()
     */
    public String getName() {
        return "udp";
    }

    /**
     * @see org.jnode.net.ipv4.IPv4Protocol#getProtocolID()
     */
    public int getProtocolID() {
        return IPPROTO_UDP;
    }

    /**
     * @see org.jnode.net.ipv4.IPv4Protocol#receive(org.jnode.net.SocketBuffer)
     */
    public void receive(SocketBuffer skbuf) throws SocketException {

        stat.ipackets.inc();

        final UDPHeader hdr = new UDPHeader(skbuf);
        if (!hdr.isChecksumOk()) {
            stat.badsum.inc();
            return;
        }

        // Set the UDP header in the buffer-field
        skbuf.setTransportLayerHeader(hdr);
        // Remove the UDP header from the head of the buffer
        skbuf.pull(hdr.getLength());
        // Trim the buffer up to the length in the UDP header
        skbuf.trim(hdr.getDataLength());

        // Test the length of the buffer to the datalength in the header.
        if (skbuf.getSize() < hdr.getDataLength()) {
            stat.badlen.inc();
            return;
        }

        // Syslog.debug("Found UDP: " + hdr);

        deliver(hdr, skbuf);
    }

    /**
     * Process an ICMP error message that has been received and matches this
     * protocol. The skbuf is position directly after the ICMP header (thus
     * contains the error IP header and error transport layer header). The
     * transportLayerHeader property of skbuf is set to the ICMP message header.
     * 
     * @param skbuf
     * @throws SocketException
     */
    public void receiveError(SocketBuffer skbuf) throws SocketException {
        // TODO handle ICMP errors in UDP
    }

    /**
     * Gets the SocketImplFactory of this protocol.
     * 
     * @throws SocketException If this protocol is not Socket based.
     */
    public SocketImplFactory getSocketImplFactory() throws SocketException {
        throw new SocketException("UDP is packet based");
    }

    /**
     * Gets the DatagramSocketImplFactory of this protocol.
     */
    public DatagramSocketImplFactory getDatagramSocketImplFactory() {
        return dsiFactory;
    }

    /**
     * Deliver a given packet to all interested sockets.
     * 
     * @param hdr
     * @param skbuf
     */
    private synchronized void deliver(UDPHeader hdr, SocketBuffer skbuf) throws SocketException {
        final Integer lport = new Integer(hdr.getDstPort());
        final IPv4Header ipHdr = (IPv4Header) skbuf.getNetworkLayerHeader();
        final UDPDatagramSocketImpl socket = (UDPDatagramSocketImpl) sockets.get(lport);
        if (socket != null) {
            final InetAddress laddr = socket.getLocalAddress();
            if (laddr.isAnyLocalAddress() || laddr.equals(ipHdr.getDestination().toInetAddress())) {
                if (socket.deliverReceived(skbuf)) {
                    return;
                }
            }
            if (ipHdr.getDestination().isMulticast()) {
                if (socket.deliverReceived(skbuf)) {
                    return;
                }
            }
        }
        stat.noport.inc();
        if (ipHdr.getDestination().isBroadcast()) {
            stat.noportbcast.inc();
        }
        if (ipHdr.getDestination().isMulticast()) {
        	// No ICMP for a multicast address with no listener
        	return;
        	
        }
        // Send a port unreachable back
        icmp.sendPortUnreachable(skbuf);
    }

    /**
     * Register a datagram socket
     * 
     * @param socket
     */
    protected synchronized void bind(UDPDatagramSocketImpl socket) throws SocketException {
        Integer lport = new Integer(socket.getLocalPort());

        if (lport.compareTo(zero) != 0 && sockets.containsKey(lport)) {
            throw new SocketException("Port already bound (" + lport + ")");
        } else {
            Integer ran;

            while (lport.compareTo(zero) == 0) {
                ran = new Integer(random.nextInt(stopRandom) + startRandom);

                if (!sockets.containsKey(ran)) {
                    // hmmm..... should we have one stop condition more??
                    lport = ran;
                    socket.setLocalPort(lport.intValue());
                }
            }

            sockets.put(lport, socket);
        }
    }

    /**
     * Unregister a datagram socket
     * 
     * @param socket
     */
    protected synchronized void unbind(UDPDatagramSocketImpl socket) {
        final Integer lport = new Integer(socket.getLocalPort());
        if (sockets.get(lport) == socket) {
            sockets.remove(lport);
        }
    }

    /**
     * Send an UDP packet
     *
     * @param skbuf
     */
    protected void send(IPv4Header ipHdr, UDPHeader udpHdr, SocketBuffer skbuf)
        throws SocketException {
        skbuf.setTransportLayerHeader(udpHdr);
        udpHdr.prefixTo(skbuf);
        ipService.transmit(ipHdr, skbuf);
        stat.opackets.inc();
    }

    /**
     * @see org.jnode.net.ipv4.IPv4Protocol#getStatistics()
     */
    public Statistics getStatistics() {
        return stat;
    }
}
