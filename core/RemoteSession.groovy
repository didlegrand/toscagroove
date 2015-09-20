package core

import java.sql.Timestamp

import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session

/**
 * SSH session to execute remote shells with context management (in/out)
 * 
 * @author Didier Legrand
 *
 */
class RemoteSession {

	static trace_on=true

	String host
	int port
	String user
	String identity
	Session session
	UUID session_id
	Channel channel
	Map context = [:]

	public RemoteSession(myHost, int myPort=22, myUser, myIdentity) {
		host = myHost
		port = myPort
		user = myUser
		identity = myIdentity
	}

	static void trace(String s) {
		if (trace_on) {
			def ts = new Timestamp(new Date().getTime())
			println "$ts TRACE $s"
		}
	}
	
	/**
	 * end the session
	 */
	public void disconnect() {
		if (session != null) {
			session.disconnect()
			session = null
		}
	}

	/**
	 * copy a remote file to local
	 * @param remoteFileName
	 * @param localFileName
	 * @return 0 if success, 1 if failure
	 */
	public int copyRemoteFiletoLocal(String remoteFileName, String localFileName) {
		trace("copyRemoteFiletoLocal($remoteFileName, $localFileName)")
		check_session()

		String prefix=null
		if(new File(localFileName).isDirectory()) {
			prefix=localFileName+File.separator
			trace("prefix=$prefix")
		}

		// exec 'scp -f rfile' remotely
		String command="scp -f "+remoteFileName
		Channel channel=session.openChannel("exec")
		((ChannelExec)channel).setCommand(command)

		// get I/O streams for remote scp
		OutputStream out=channel.getOutputStream()
		InputStream ins=channel.getInputStream()

		channel.connect()
		trace("connected.")

		byte[] buf=new byte[1024];

		// send '\0'
		buf[0]=0; out.write(buf, 0, 1); out.flush()

		while(true){
			int c=checkAck(ins);
			if(c!='C') {
				break
			}

			// read '0644 '
			ins.read(buf, 0, 5)

			long filesize=0L
			while(true){
				if(ins.read(buf, 0, 1)<0) {
					// error
					break
				}
				if(buf[0]==' ')
					break
				char cl = buf[0]
				char c0 = '0'
				long len = cl - c0
				filesize=filesize*10L+len
			}

			String file=null
			for(int i=0;;i++) {
				ins.read(buf, i, 1)
				if(buf[i]==(byte)0x0a) {
					file=new String(buf, 0, i)
					break
				}
			}

			// send '\0'
			buf[0]=0
			out.write(buf, 0, 1)
			out.flush()

			// read a content of lfile
			FileOutputStream fos=null;
			fos=new FileOutputStream(prefix==null ? localFileName : prefix+localFileName)
			int foo
			while(true) {
				if(buf.length<filesize) foo=buf.length
				else foo=(int)filesize
				foo=ins.read(buf, 0, foo)
				if(foo<0) {
					// error
					break
				}
				fos.write(buf, 0, foo)
				filesize-=foo
				if(filesize==0L) break
			}
			fos.close()
			fos=null
			trace "remote file copied."

			if(checkAck(ins)!=0){
				return 1
			}

			// send '\0'
			buf[0]=0; out.write(buf, 0, 1); out.flush()
		}

		return 0
	}

	static private int checkAck(InputStream ins) throws IOException{
		int b=ins.read();
		// b may be 0 for success,
		//          1 for error,
		//          2 for fatal error,
		//          -1
		if(b==0) return b;
		if(b==-1) return b;

		if(b==1 || b==2){
			StringBuffer sb=new StringBuffer();
			int c;
			while(c!='\n') {
				c=ins.read();
				sb.append((char)c);
			}

			if(b==1) { // error
				System.out.print(sb.toString());
			}
			if(b==2) { // fatal error
				System.out.print(sb.toString());
			}
		}
		return b
	}

	/**
	 * Execute a shell file.
	 * @param shellFile script file to be executed
	 * @param output optionally save the shell output in it
	 * @return 0 if success, 1 if failure
	 */
	public int execute(File shellFile, StringBuilder output=null) {
		String shell = shellFile?.text.replaceAll("\r", "")
		assert shell != null

		trace "executing $shellFile.name..."
		return execute(shell, output)
	}

	// create a session if there is no one
	private void check_session() {
		if (session == null) {
			trace "creating session..."
			JSch jsch = new JSch()
			jsch.addIdentity(identity)
			session = jsch.getSession(user, host, port)
			session.setConfig("StrictHostKeyChecking", "no")
			session.connect(30000) // making a connection with timeout.
			session_id = UUID.randomUUID()
			trace "session created: $session_id"
		}
	}

	// create a remote shell to init env variables
	private String setEnvScript() {
		StringBuilder shell = new StringBuilder()
		if (context.size() > 0) {
			context.each{ key, value ->
				trace "setting env CTX_$key=$value"
				shell << "export CTX_$key=$value\n"
			}
		}
		return shell.toString()
	}

	// save updated CTX_ env values in a temp file
	private String saveEnvScript() {
		StringBuilder shell = new StringBuilder()
		if (context.size() > 0) {
			def tmpFile = "/tmp/$session_id"
			shell << "\nenv | grep 'CTX_.*=' > $tmpFile"
		}
		trace "save env. script:\n${shell.toString()}"
		return shell.toString()
	}

	// reads the remote context file and update the local context map
	private void update_context() {
		trace "récupération du contexte mis à jour"
		copyRemoteFiletoLocal("/tmp/$session_id", "data/$session_id")
		File f = new File("data/$session_id")
		if (f.exists()) {
			f.text.eachLine() { line ->
				trace "- variable $line"
				def t = line.split("=")
				def key = t[0].replace("CTX_", "")
				def value = t.size() > 1?t[1]:""
				context[key] = value
			}
			f.delete()
		}
	}

	/**
	 * Execute a shell command (possibly several separated wit ';' or '\n'.
	 * @param output optionally save the shell output in it
	 * @param cmd shell command(s) to be executed
	 * @return 0 if success, 1 if failure
	 */
	public int execute(String cmd, StringBuilder output=null) {
		trace "execute( $cmd, ${output == null?'no output':'with output'} )"
		check_session()

		channel = session.openChannel("exec")
		def script = setEnvScript()+cmd+saveEnvScript()
		trace "full script:\n$script"
		((ChannelExec) channel).setCommand(script)
		channel.setInputStream(null)
		channel.setOutputStream(null)
		((ChannelExec) channel).setErrStream(System.err)
		channel.connect()
		String s = getOutput(channel)
		trace "command output:\n$s"
		if (output != null) {
			output << s
		}
		int exitStatus = channel.getExitStatus()
		update_context()
		channel.disconnect()
		return exitStatus
	}

	static private String getOutput(Channel channel) throws Exception {
		InputStream ins = channel.getInputStream()
		StringBuilder result = new StringBuilder()
		byte[] tmp = new byte[1024]
		while (true) {
			while (ins.available() > 0) {
				int i = ins.read(tmp, 0, 1024)
				if (i < 0)
					break
				result.append(new String(tmp, 0, i))
			}
			if (channel.isClosed()) {
				if (ins.available() > 0)
					continue;
				break
			}
			try {
				Thread.sleep(1000)
			} catch (Exception ee) {
			}
		}
		return result.toString()
	}
}
