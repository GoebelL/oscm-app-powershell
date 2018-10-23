/*******************************************************************************
 *
 *  COPYRIGHT (C) 2013 FUJITSU Limited - ALL RIGHTS RESERVED. 
 *
 *  Creation Date: 01.02.2013
 *                                                                              
 *******************************************************************************/
package org.oscm.app.powershell.business.api;

import static java.lang.String.join;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.oscm.app.powershell.business.api.PowershellStatus.CALLERID_DOES_NOT_MATCH;
import static org.oscm.app.powershell.business.api.PowershellStatus.PSSHELL_ERROR;
import static org.oscm.app.powershell.business.api.PowershellStatus.RUNNING;
import static org.oscm.app.powershell.business.api.PowershellStatus.STDIN_CLOSED;
import static org.oscm.app.powershell.business.api.PowershellStatus.SUCCESS;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.oscm.app.exceptions.APPlatformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PowerShell runtime used to execute PowerShell scripts
 */
public class Powershell implements AutoCloseable {

    public static final String VERIFICATION_MESSAGE = "VERIFICATION_MESSAGE";

    private static final Logger LOG = LoggerFactory.getLogger(Powershell.class);

    private final Process powershell;

    /**
     * Indicates if a shell is in use. A shell is free if the callerid is null.
     * Otherwise an APP instance set any callerid to lock the shell and additionally
     * to aquire the shell again by the callerid.
     */
    private volatile String lockId;

    /**
     * Input stream of PowerShell console
     */
    private final BufferedWriter stdIn;

    /**
     * Output of the PowerShell console
     */
    private final StreamGobbler stdOut;

    /**
     * Error stream of PowerShell console
     */
    private final StreamGobbler stdErr;

    private PowershellCommand command;

    public Powershell() throws IOException, APPlatformException {
	this(null);
    }

    public Powershell(String psconsole) throws IOException, APPlatformException {
	if (psconsole == null || psconsole.isEmpty()) {
	    psconsole = "powershell -ExecutionPolicy Bypass -NoExit -";
	} else {
	    psconsole = "powershell -PSConsoleFile \"" + psconsole + "\" -ExecutionPolicy Bypass -NoExit -";
	}

	powershell = Runtime.getRuntime().exec(psconsole);

	stdIn = new BufferedWriter(new OutputStreamWriter(powershell.getOutputStream()));
	stdOut = new StreamGobbler(powershell.getInputStream());
	stdErr = new StreamGobbler(powershell.getErrorStream());

	if (!stdErr.buffer.isEmpty()) {
	    throw new APPlatformException(
		    "Powershell initialization problem, error stream not empty: " + join("", stdErr.buffer));
	}

	if (!stdOut.buffer.isEmpty()) {
	    stdOut.buffer.clear();
	}

	lockId = null;
    }

    public PowershellStatus runCommand(final String lockId, final PowershellCommand command) {
	if (!lockId.equals(this.lockId)) {
	    LOG.error("shell called by " + lockId + ", but locked for " + this.lockId);
	    return CALLERID_DOES_NOT_MATCH;
	}

	this.command = command;
	try {
	    LOG.debug(String.format("lockId: %s, command:\n%s", lockId, command.getCommand()));
	    stdIn.write(command.getCommand());
	    stdIn.newLine();
	    stdIn.flush();
	} catch (IOException e) {
	    LOG.error("lockId: " + lockId + " failed to write command to shell stdin", e);
	    return STDIN_CLOSED;
	}

	return RUNNING;
    }

    public String getOutput(String lockId) {
	StringBuffer sb = new StringBuffer();
	for (String line : command.getOutput()) {
	    sb.append(line);
	    sb.append("\n\t");
	}
	String output = sb.toString();
	LOG.trace("lockId: " + lockId + " found shell with output: " + output);
	return output;
    }

    public String getErrorOutput(String lockId) {
	StringBuffer sb = new StringBuffer();
	for (String line : command.getError()) {
	    sb.append(line);
	    sb.append("\n\t");
	}
	String output = sb.toString();
	LOG.trace("lockId: " + lockId + " found shell with error output: " + output);
	return output;
    }

    public PowershellStatus consumeOutput(String lockId) {
	if (!lockId.equals(this.lockId)) {
	    LOG.error("shell called by " + lockId + ", but locked for " + this.lockId);
	    return CALLERID_DOES_NOT_MATCH;
	}
	if (command.getReturnCode() == SUCCESS) {
	    return SUCCESS;
	}

	return getCmdOutput();
    }

    public void unlock() {
	LOG.trace("callerid: " + lockId + " shell has been unlocked");
	lockId = null;
    }

    /**
     * returns lock status of PowerShell runtime
     * 
     * @return lock status: true, if shell is locked / false, if shell is free
     */
    public boolean isLocked() {
	return (lockId == null ? false : true);
    }

    /**
     * returns lock status of PowerShell runtime utilizing id of calling command
     * from API class
     * 
     * @return caller id, if shell is locked / empty string, if shell is free
     */
    public String isLockedFor() {
	return (lockId == null ? "" : lockId);
    }

    /**
     * locks PowerShell runtime, if unlocked
     * 
     * @return lock status: true, if shell has been free and is now locked / false,
     *         if shell was already locked and could not be locked
     */
    public boolean lockPowerShell(String lockId) {
	if (this.lockId == null) {
	    this.lockId = lockId;
	    return true;
	}

	return false;
    }

    /**
     * Flushes all pipes (StdIn, StdOut, StdErr), interrupts gobbler threads and
     * terminates PowerShell runtime
     */
    @Override
    public void close() {
	try {
	    stdIn.write("exit;");
	    stdIn.flush();
	} catch (IOException ioe) {
	    // ignore
	} finally {
	    silentlyCloseStdIn();
	}

	stdOut.interrupt();
	stdErr.interrupt();
	powershell.destroy();
    }

    private void silentlyCloseStdIn() {
	try {
	    stdIn.close();
	} catch (IOException e) {
	    // ignore
	}
    }

    /**
     * Capture PowerShell script output.
     * 
     * @param command
     *            the shell to consume the output from
     */
    private PowershellStatus getCmdOutput() {
	PowershellStatus status = RUNNING;

	while (!stdOut.buffer.isEmpty()) {
	    String line = stdOut.buffer.remove(0);
	    LOG.trace(String.format("lockId=%s, shell line: ", lockId, line));

	    if ("END_OF_SCRIPT".equals(line)) {
		if (!hasErrors()) {
		    status = SUCCESS;
		} else {
		    status = PSSHELL_ERROR;
		}
	    } else {
		command.addOutputLine(line);
	    }
	}

	if (!stdErr.buffer.isEmpty()) {
	    while (!stdErr.buffer.isEmpty()) {
		String errorLine = stdErr.buffer.remove(0);
		LOG.trace("callerid: " + lockId + " error line: " + errorLine);
		command.addErrorLine(errorLine);
	    }
	    status = PSSHELL_ERROR;
	}

	command.setReturnCode(status);
	return status;
    }

    private boolean hasErrors() {
	ExecutorService executor = Executors.newFixedThreadPool(1);
	Callable<Integer> readTask = new Callable<Integer>() {
	    @Override
	    public Integer call() throws Exception {
		return powershell.getErrorStream().read();
	    }
	};

	List<Integer> bytes = new ArrayList<Integer>();
	int readByte = 1;
	try {
	    while (readByte >= 0) {
		Future<Integer> future = executor.submit(readTask);
		readByte = future.get(500, MILLISECONDS);
		bytes.add(readByte);
	    }
	} catch (TimeoutException e) {
	    // ignore
	} catch (InterruptedException | ExecutionException e) {
	    return false;
	}
	return bytes.size() > 0;
    }

}
