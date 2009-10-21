/**
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author
 * tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package openr66.server;

import java.net.SocketAddress;
import java.sql.Timestamp;

import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.common.logging.GgSlf4JLoggerFactory;


import openr66.configuration.FileBasedConfiguration;
import openr66.context.ErrorCode;
import openr66.context.R66Result;
import openr66.database.DbConstant;
import openr66.database.data.DbHostAuth;
import openr66.database.data.DbTaskRunner;
import openr66.database.exception.OpenR66DatabaseNoConnectionError;
import openr66.protocol.configuration.Configuration;
import openr66.protocol.exception.OpenR66ProtocolNoConnectionException;
import openr66.protocol.exception.OpenR66ProtocolPacketException;
import openr66.protocol.localhandler.LocalChannelReference;
import openr66.protocol.localhandler.packet.LocalPacketFactory;
import openr66.protocol.localhandler.packet.ValidPacket;
import openr66.protocol.networkhandler.NetworkTransaction;
import openr66.protocol.utils.ChannelUtils;
import openr66.protocol.utils.R66Future;

import org.jboss.netty.channel.Channels;
import org.jboss.netty.logging.InternalLoggerFactory;

import ch.qos.logback.classic.Level;

/**
 * Log Export from a local client without database connection
 *
 * @author Frederic Bregier
 *
 */
public class LogExport implements Runnable {
    /**
     * Internal Logger
     */
    static volatile GgInternalLogger logger;

    protected final R66Future future;
    protected final boolean purgeLog;
    protected final Timestamp start;
    protected final Timestamp stop;
    protected final boolean clean;
    protected final NetworkTransaction networkTransaction;

    public LogExport(R66Future future, boolean purgeLog, boolean clean,
            Timestamp start, Timestamp stop,
            NetworkTransaction networkTransaction) {
        this.future = future;
        this.purgeLog = purgeLog;
        this.clean = clean;
        this.start = start;
        this.stop = stop;
        this.networkTransaction = networkTransaction;
    }

    /**
     * Prior to call this method, the pipeline and NetworkTransaction must have been initialized.
     * It is the responsibility of the caller to finish all network resources.
     */
    public void run() {
        if (logger == null) {
            logger = GgInternalLoggerFactory.getLogger(LogExport.class);
        }
        String lstart = (start != null) ? start.toString() : null;
        String lstop  = (stop != null) ? stop.toString() : null;
        byte type = (purgeLog) ? LocalPacketFactory.LOGPURGEPACKET : LocalPacketFactory.LOGPACKET;
        ValidPacket valid = new ValidPacket(lstart, lstop, type);
        DbHostAuth host = Configuration.configuration.HOST_SSLAUTH;
        SocketAddress socketAddress = host.getSocketAddress();
        boolean isSSL = host.isSsl();

        // first clean if ask
        if (clean) {
            // Update all UpdatedInfo to DONE
            // where GlobalLastStep = ALLDONETASK and status = CompleteOk
            try {
                DbTaskRunner.changeFinishedToDone(DbConstant.admin.session);
            } catch (OpenR66DatabaseNoConnectionError e) {
                logger.warn("Clean cannot be done", e);
            }
        }
        LocalChannelReference localChannelReference = networkTransaction
            .createConnectionWithRetry(socketAddress, isSSL, future);
        socketAddress = null;
        if (localChannelReference == null) {
            host = null;
            logger.error("Cannot Connect");
            future.setResult(new R66Result(
                    new OpenR66ProtocolNoConnectionException("Cannot connect to server"),
                    null, true, ErrorCode.Internal, null));
            future.setFailure(future.getResult().exception);
            return;
        }
        try {
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, valid);
        } catch (OpenR66ProtocolPacketException e) {
            logger.error("Bad Protocol", e);
            Channels.close(localChannelReference.getLocalChannel());
            localChannelReference = null;
            host = null;
            valid = null;
            future.setResult(new R66Result(e, null, true,
                    ErrorCode.TransferError, null));
            future.setFailure(e);
            return;
        }
        host = null;
        future.awaitUninterruptibly();
        logger.info("Request done with "+(future.isSuccess()?"success":"error"));
        Channels.close(localChannelReference.getLocalChannel());
        localChannelReference = null;
    }

    protected static boolean spurgeLog = false;
    protected static Timestamp sstart = null;
    protected static Timestamp sstop = null;
    protected static boolean sclean = false;

    protected static boolean getParams(String [] args) {
        if (args.length < 1) {
            logger.error("Need at least the configuration file as first argument");
            return false;
        }
        if (! FileBasedConfiguration
                .setClientConfigurationFromXml(args[0])) {
            logger
                    .error("Needs a correct configuration file as first argument");
            return false;
        }
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("-purge")) {
                spurgeLog = true;
            } else if (args[i].equalsIgnoreCase("-clean")) {
                sclean = true;
            } else if (args[i].equalsIgnoreCase("-start")) {
                i++;
                sstart = Timestamp.valueOf(args[i]);
            } else if (args[i].equalsIgnoreCase("-stop")) {
                i++;
                sstop = Timestamp.valueOf(args[i]);
            }
        }
        return true;
    }

    public static void main(String[] args) {
        InternalLoggerFactory.setDefaultFactory(new GgSlf4JLoggerFactory(
                Level.WARN));
        if (logger == null) {
            logger = GgInternalLoggerFactory.getLogger(LogExport.class);
        }
        if (! getParams(args)) {
            logger.error("Wrong initialization");
            if (DbConstant.admin != null && DbConstant.admin.isConnected) {
                DbConstant.admin.close();
            }
            System.exit(1);
        }
        long time1 = System.currentTimeMillis();
        R66Future future = new R66Future(true);

        Configuration.configuration.pipelineInit();
        NetworkTransaction networkTransaction = new NetworkTransaction();
        try {
            LogExport transaction = new LogExport(future,
                    spurgeLog, sclean, sstart, sstop,
                    networkTransaction);
            transaction.run();
            future.awaitUninterruptibly();
            long time2 = System.currentTimeMillis();
            long delay = time2 - time1;
            R66Result result = future.getResult();
            if (future.isSuccess()) {
                if (result.code == ErrorCode.Warning) {
                    logger.warn("WARNED on file:\n    " +
                            (result.other != null? ((ValidPacket)result.other).getSheader() :
                                "no file")
                            +"\n    delay: "+delay);
                } else {
                    logger.warn("SUCCESS on Final file:\n    " +
                            (result.other != null? ((ValidPacket)result.other).getSheader() :
                            "no file")
                            +"\n    delay: "+delay);
                }
            } else {
                if (result.code == ErrorCode.Warning) {
                    logger.warn("Transfer is\n    WARNED", future.getCause());
                    networkTransaction.closeAll();
                    System.exit(result.code.ordinal());
                } else {
                    logger.error("Transfer in\n    FAILURE", future.getCause());
                    networkTransaction.closeAll();
                    System.exit(result.code.ordinal());
                }
            }
        } finally {
            networkTransaction.closeAll();
        }
    }

}
