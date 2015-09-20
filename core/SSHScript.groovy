package core

class SSHScript {
	
	private String hostname
	private int port
	private String user
	private String identity_file
	private String script
	private Map context = [:]
	
	public int execute() {
		def session = new RemoteSession(hostname, port, user, identity_file)
		context.each { key, value -> session.context[key] = value }
		int cr = session.execute(script)
		context = [:]
		session.context.each { key, value -> context[key] = value }
		return cr
	}
	
	public String toString() {
		"ssh -i$identity_file $user@$hostname $script"
	}

}
