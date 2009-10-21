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
package openr66.protocol.localhandler;

import java.util.concurrent.atomic.AtomicBoolean;

import goldengate.common.file.DataBlock;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import openr66.context.ErrorCode;
import openr66.context.R66Result;
import openr66.context.R66Session;
import openr66.context.task.exception.OpenR66RunnerErrorException;
import openr66.database.data.DbTaskRunner.TASKSTEP;
import openr66.protocol.configuration.Configuration;
import openr66.protocol.exception.OpenR66Exception;
import openr66.protocol.exception.OpenR66ProtocolPacketException;
import openr66.protocol.exception.OpenR66ProtocolSystemException;
import openr66.protocol.localhandler.packet.EndRequestPacket;
import openr66.protocol.localhandler.packet.ErrorPacket;
import openr66.protocol.networkhandler.NetworkServerHandler;
import openr66.protocol.networkhandler.NetworkTransaction;
import openr66.protocol.utils.ChannelUtils;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;

/**
 * Retrieve transfer runner
 * @author Frederic Bregier
 *
 */
public class RetrieveRunner extends Thread {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(RetrieveRunner.class);

    private final R66Session session;

    private final LocalChannelReference localChannelReference;

    private final Channel channel;

    private boolean done = false;

    private AtomicBoolean running = new AtomicBoolean(true);

    /**
     *
     * @param session
     * @param channel local channel
     */
    public RetrieveRunner(R66Session session, Channel channel) {
        this.session = session;
        localChannelReference = this.session.getLocalChannelReference();
        this.channel = channel;
    }

    /**
     * Try to stop the runner
     */
    public void stopRunner() {
        running.set(false);
        /*boolean isinterrupted = false;
        try {
            Thread.sleep(Configuration.WAITFORNETOP);
        } catch (InterruptedException e) {
            isinterrupted = true;
        }
        this.interrupt();
        if (isinterrupted) {
            Thread.currentThread().interrupt();
        }*/
    }
    /*
     * (non-Javadoc)
     *
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        try {
            Thread.currentThread().setName("RetrieveRunner: " + channel.getId());
            try {
                if (session.getRunner().getGloballaststep() == TASKSTEP.POSTTASK.ordinal()) {
                    // restart from PostTask global step so just end now
                    try {
                        ChannelUtils.writeEndTransfer(localChannelReference);
                    } catch (OpenR66ProtocolPacketException e) {
                        transferInError(e);
                        logger.warn("End Retrieve in Error");
                        return;
                    }
                }
                session.getFile().retrieveBlocking(running);
            } catch (OpenR66RunnerErrorException e) {
                transferInError(e);
                logger.warn("End Retrieve in Error");
                return;
            } catch (OpenR66ProtocolSystemException e) {
                transferInError(e);
                logger.warn("End Retrieve in Error");
                return;
            }
            try {
                localChannelReference.getFutureEndTransfer().await();
            } catch (InterruptedException e1) {
            }
            logger.info("Await future End Transfer done: " +
                    localChannelReference.getFutureEndTransfer().isSuccess());
            if (localChannelReference.getFutureEndTransfer().isSuccess()) {
                // send a validation
                EndRequestPacket validPacket = new EndRequestPacket(ErrorCode.CompleteOk.ordinal());
                try {
                    ChannelUtils.writeAbstractLocalPacket(localChannelReference, validPacket).awaitUninterruptibly();
                } catch (OpenR66ProtocolPacketException e) {
                }
                localChannelReference.RequestIsDone();
                if (!localChannelReference.getFutureRequest().awaitUninterruptibly(
                        Configuration.WAITFORNETOP*10)) {
                    // valid it however
                    localChannelReference.validateRequest(localChannelReference
                        .getFutureEndTransfer().getResult());
                }
                if (session.getRunner() != null && session.getRunner().isSelfRequested()) {
                    ChannelUtils.close(localChannelReference.getLocalChannel());
                }
                done = true;
            } else {
                if (localChannelReference.getFutureEndTransfer().isDone()) {
                    if (!localChannelReference.getFutureEndTransfer().getResult().isAnswered) {
                        ErrorPacket error = new ErrorPacket("Transfer in error",
                                ErrorCode.TransferError.getCode(), ErrorPacket.FORWARDCLOSECODE);
                        try {
                            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error)
                                .awaitUninterruptibly();
                        } catch (OpenR66ProtocolPacketException e) {
                        }
                    }
                }
                if (!localChannelReference.getFutureRequest().isDone()) {
                    localChannelReference.invalidateRequest(localChannelReference
                        .getFutureEndTransfer().getResult());
                }
                done = true;
                logger.warn("End Retrieve in Error");
            }
        } finally {
            if (!done) {
                if (localChannelReference.getFutureEndTransfer().isSuccess()) {
                    if (! localChannelReference.isRequestDone()) {
                        EndRequestPacket validPacket = new EndRequestPacket(ErrorCode.CompleteOk.ordinal());
                        try {
                            ChannelUtils.writeAbstractLocalPacket(localChannelReference, validPacket).awaitUninterruptibly();
                        } catch (OpenR66ProtocolPacketException e) {
                        }
                    }
                    localChannelReference.validateRequest(localChannelReference
                            .getFutureEndTransfer().getResult());
                    if (session.getRunner() != null && session.getRunner().isSelfRequested()) {
                        ChannelUtils.close(localChannelReference.getLocalChannel());
                    }
                } else {
                    if (localChannelReference.getFutureEndTransfer().isDone()) {
                        if (!localChannelReference.getFutureEndTransfer().getResult().isAnswered) {
                            ErrorPacket error = new ErrorPacket("Transfer in error",
                                    ErrorCode.TransferError.getCode(), ErrorPacket.FORWARDCLOSECODE);
                            try {
                                ChannelUtils.writeAbstractLocalPacket(localChannelReference, error)
                                    .awaitUninterruptibly();
                            } catch (OpenR66ProtocolPacketException e) {
                            }
                        }
                    } else {
                        localChannelReference.invalidateRequest(localChannelReference
                            .getFutureEndTransfer().getResult());
                    }
                }
            }
            NetworkTransaction.normalEndRetrieve(localChannelReference);
        }
    }
    private void transferInError(OpenR66Exception e) {
        R66Result result = new R66Result(e, session, true,
                ErrorCode.TransferError, session.getRunner());
        localChannelReference.invalidateRequest(result);
        logger.error("Transfer in error", e);
        ErrorPacket error = new ErrorPacket("Transfer in error",
                ErrorCode.TransferError.getCode(), ErrorPacket.FORWARDCLOSECODE);
        try {
            ChannelUtils.writeAbstractLocalPacket(localChannelReference, error).awaitUninterruptibly();
        } catch (OpenR66ProtocolPacketException e1) {
        }
        ChannelUtils.close(channel);
        done = true;
    }
    /**
     * Write the next block when the channel is ready to prevent OOM
     * @param block
     * @param localChannelReference
     * @return the ChannelFuture on the write operation
     * @throws OpenR66ProtocolPacketException
     * @throws OpenR66RunnerErrorException
     * @throws OpenR66ProtocolSystemException
     */
    public static ChannelFuture writeWhenPossible(
            DataBlock block, LocalChannelReference localChannelReference)
        throws OpenR66ProtocolPacketException, OpenR66RunnerErrorException,
            OpenR66ProtocolSystemException {
        // Test if channel is writable in order to prevent OOM
        R66Session session = localChannelReference.getSession();
        NetworkServerHandler serverHandler = localChannelReference.getNetworkServerHandler();
        if (serverHandler.isWritable()) {
            return ChannelUtils.writeBackDataBlock(localChannelReference, block);
        } else {
            // Wait for the next InterestChanged
            while (serverHandler.isWriteReady()) {
                try {
                    Thread.sleep(Configuration.RETRYINMS);
                } catch (InterruptedException e) {
                    // Exception while waiting
                    session.setFinalizeTransfer(
                            false,
                            new R66Result(
                                    new OpenR66ProtocolSystemException(
                                            e), session, false,
                                    ErrorCode.Internal, session.getRunner()));
                    throw new OpenR66RunnerErrorException("Interruption while waiting", e);
                }
            }
            return ChannelUtils.writeBackDataBlock(localChannelReference, block);
        }
    }
    /**
     * Utility method for send through mode
     * @param data the data byte, if null it is the last block
     * @return the DataBlock associated to the data
     */
    public static DataBlock transformToDataBlock(byte []data) {
        DataBlock block = new DataBlock();
        if (data == null) {
            // last block
            block.setEOF(true);
        } else {
            block.setBlock(ChannelBuffers.wrappedBuffer(data));
        }
        return block;
    }
}
