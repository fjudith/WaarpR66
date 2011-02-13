/**
   This file is part of GoldenGate Project (named also GoldenGate or GG).

   Copyright 2009, Frederic Bregier, and individual contributors by the @author
   tags. See the COPYRIGHT.txt in the distribution for a full listing of
   individual contributors.

   All GoldenGate Project is free software: you can redistribute it and/or 
   modify it under the terms of the GNU General Public License as published 
   by the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   GoldenGate is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.

   You should have received a copy of the GNU General Public License
   along with GoldenGate .  If not, see <http://www.gnu.org/licenses/>.
 */
package openr66.context.task;

import goldengate.commandexec.utils.LocalExecResult;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import openr66.context.ErrorCode;
import openr66.context.R66Result;
import openr66.context.R66Session;
import openr66.context.task.exception.OpenR66RunnerErrorException;
import openr66.context.task.localexec.LocalExecClient;
import openr66.protocol.configuration.Configuration;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

/**
 * Execute an external command and Use the output if an error occurs.
 *
 * 
 * FIXME add LocalExec support
 *
 * @author Frederic Bregier
 *
 */
public class ExecOutputTask extends AbstractTask {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(ExecOutputTask.class);

    /**
     * @param argRule
     * @param delay
     * @param argTransfer
     * @param session
     */
    public ExecOutputTask(String argRule, int delay, String argTransfer,
            R66Session session) {
        super(TaskType.EXECOUTPUT, delay, argRule, argTransfer, session);
    }

    /*
     * (non-Javadoc)
     *
     * @see openr66.context.task.AbstractTask#run()
     */
    @Override
    public void run() {
        /*
         * First apply all replacements and format to argRule from context and
         * argTransfer. Will call exec (from first element of resulting string)
         * with arguments as the following value from the replacements. Return 0
         * if OK, else 1 for a warning else as an error. 
         * In case of an error (> 0), all the line from output will be
         * send back to the partner with the Error code.
         * No change is made to the file.
         */
        logger.info("ExecOutput with " + argRule + ":" + argTransfer + " and {}",
                session);
        String finalname = argRule;
        finalname = getReplacedValue(finalname, argTransfer.split(" "));
        if (Configuration.configuration.useLocalExec) {
            LocalExecClient localExecClient = new LocalExecClient();
            if (localExecClient.connect()) {
                localExecClient.runOneCommand(finalname, delay, futureCompletion);
                LocalExecResult result = localExecClient.getLocalExecResult();
                finalize(result.status, result.result, finalname);
                localExecClient.disconnect();
                return;
            } // else continue
        }
        String[] args = finalname.split(" ");
        File exec = new File(args[0]);
        if (exec.isAbsolute()) {
            if (! exec.canExecute()) {
                logger.error("Exec command is not executable: " + finalname);
                R66Result result = new R66Result(session, false,
                        ErrorCode.CommandNotFound, session.getRunner());
                futureCompletion.setResult(result);
                futureCompletion.cancel();
                return;
            }
        }
        CommandLine commandLine = new CommandLine(args[0]);
        for (int i = 1; i < args.length; i ++) {
            commandLine.addArgument(args[i]);
        }
        DefaultExecutor defaultExecutor = new DefaultExecutor();
        PipedInputStream inputStream = new PipedInputStream();
        PipedOutputStream outputStream = null;
        try {
            outputStream = new PipedOutputStream(inputStream);
        } catch (IOException e1) {
            try {
                inputStream.close();
            } catch (IOException e) {
            }
            logger.error("Exception: " + e1.getMessage() +
                    " Exec in error with " + commandLine.toString(), e1);
            futureCompletion.setFailure(e1);
            return;
        }
        PumpStreamHandler pumpStreamHandler = new PumpStreamHandler(
                outputStream, null);
        defaultExecutor.setStreamHandler(pumpStreamHandler);
        int[] correctValues = {
                0, 1 };
        defaultExecutor.setExitValues(correctValues);
        ExecuteWatchdog watchdog = null;
        if (delay > 0) {
            watchdog = new ExecuteWatchdog(delay);
            defaultExecutor.setWatchdog(watchdog);
        }
        AllLineReader allLineReader = new AllLineReader(inputStream);
        Thread thread = new Thread(allLineReader);
        thread.setDaemon(true);
        thread.setName("ExecRename" + session.getRunner().getSpecialId());
        thread.start();
        int status = -1;
        try {
            status = defaultExecutor.execute(commandLine);
        } catch (ExecuteException e) {
            if (e.getExitValue() == -559038737) {
                // Cannot run immediately so retry once
                try {
                    Thread.sleep(Configuration.RETRYINMS);
                } catch (InterruptedException e1) {
                }
                try {
                    status = defaultExecutor.execute(commandLine);
                } catch (ExecuteException e1) {
                    finalizeFromError(outputStream, 
                            pumpStreamHandler, 
                            inputStream, 
                            allLineReader, 
                            thread, 
                            status, 
                            commandLine);
                    return;
                } catch (IOException e1) {
                    try {
                        outputStream.flush();
                    } catch (IOException e2) {
                    }
                    try {
                        outputStream.close();
                    } catch (IOException e2) {
                    }
                    thread.interrupt();
                    try {
                        inputStream.close();
                    } catch (IOException e2) {
                    }
                    pumpStreamHandler.stop();
                    logger.error("IOException: " + e.getMessage() +
                            " . Exec in error with " + commandLine.toString());
                    futureCompletion.setFailure(e);
                    return;
                }
            } else {
                finalizeFromError(outputStream, 
                        pumpStreamHandler, 
                        inputStream, 
                        allLineReader, 
                        thread, 
                        status, 
                        commandLine);
                return;
            }
        } catch (IOException e) {
            try {
                outputStream.close();
            } catch (IOException e1) {
            }
            thread.interrupt();
            try {
                inputStream.close();
            } catch (IOException e1) {
            }
            pumpStreamHandler.stop();
            logger.error("IOException: " + e.getMessage() +
                    " . Exec in error with " + commandLine.toString());
            futureCompletion.setFailure(e);
            return;
        }
        try {
            outputStream.flush();
        } catch (IOException e) {
        }
        try {
            outputStream.close();
        } catch (IOException e) {
        }
        pumpStreamHandler.stop();
        try {
            if (delay > 0) {
                thread.join(delay);
            } else {
                thread.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            inputStream.close();
        } catch (IOException e1) {
        }
        String newname = null;
        if (defaultExecutor.isFailure(status) && watchdog != null &&
                watchdog.killedProcess()) {
            // kill by the watchdoc (time out)
            status = -1;
            newname = "TimeOut";
        } else {
            newname = allLineReader.lastLine.toString();
        }
        finalize(status, newname, commandLine.toString());
    }

    private void finalize(int status, String newname, String commandLine) {
        if (status == 0) {
            futureCompletion.setSuccess();
            logger.info("Exec OK with {} returns {}", commandLine,
                    newname);
        } else if (status == 1) {
            logger.warn("Exec in warning with " + commandLine+
                    " returns " + newname);
            session.getRunner().setErrorExecutionStatus(ErrorCode.Warning);
            futureCompletion.setSuccess();
        } else {
            logger.error("Status: " + status + " Exec in error with " +
                    commandLine + " returns " + newname);
            OpenR66RunnerErrorException exc = 
                new OpenR66RunnerErrorException("Status: " + status + 
                        "\n<ERROR>" + newname+"</ERROR>");
            futureCompletion.setFailure(exc);
        }
    }
    private void finalizeFromError(PipedOutputStream outputStream, 
            PumpStreamHandler pumpStreamHandler,
            PipedInputStream inputStream, AllLineReader allLineReader, Thread thread,
            int status, CommandLine commandLine) {
        try {
            Thread.sleep(Configuration.RETRYINMS);
        } catch (InterruptedException e) {
        }
        try {
            outputStream.flush();
        } catch (IOException e2) {
        }
        try {
            Thread.sleep(Configuration.RETRYINMS);
        } catch (InterruptedException e) {
        }
        try {
            outputStream.close();
        } catch (IOException e1) {
        }
        thread.interrupt();
        try {
            inputStream.close();
        } catch (IOException e1) {
        }
        try {
            Thread.sleep(Configuration.RETRYINMS);
        } catch (InterruptedException e) {
        }
        pumpStreamHandler.stop();
        try {
            Thread.sleep(Configuration.RETRYINMS);
        } catch (InterruptedException e) {
        }
        String result = allLineReader.lastLine.toString();
        logger.error("Status: " + status + " Exec in error with " +
                commandLine + " returns\n" + result);
        OpenR66RunnerErrorException exc = 
            new OpenR66RunnerErrorException("Status: " + status + 
                    "\n<ERROR>" + result+"</ERROR>");
        futureCompletion.setFailure(exc);
    }
}
