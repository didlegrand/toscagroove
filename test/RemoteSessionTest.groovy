package test;

import static org.junit.Assert.*

import org.junit.After
import org.junit.Before
import org.junit.Test

import core.RemoteSession

class RemoteSessionTest {

	static RemoteSession session

	@Before
	public void before() {
		session = new RemoteSession(Config.myHost, Config.myPort, Config.myUser, Config.myIdentityFile)
	}

	@After
	public void after() {
		session.disconnect()
	}

	@Test
	public void testSimpleShell() {
		assert session.execute( new File("shell/test_shell0.sh")) == 0
	}

	@Test
	public void testMoreThanOneShell() {
		assert session.execute( new File("shell/test_shell0.sh")) == 0
		assert session.execute(new File("shell/test_shell.sh")) == 0
		assert session.execute(new File("shell/bad_shell.sh")) == 1
	}

	@Test
	public void testSimpleCmd() {
		assert session.execute( "pwd" ) == 0
	}

	@Test
	public void testSimpleCmdWithOutput() {
		def output = new StringBuilder()
		assert session.execute( "pwd", output ) == 0
		assert output.toString().contains("/home/${Config.myUser}")
	}

	@Test
	public void testMoreThanOneCmd() {
		assert session.execute( "export TEST_VAR=${new Date()}; echo \$\$ \$TEST_VAR" ) == 0
		assert session.execute( "env | grep TEST_VAR; echo \$\$" ) == 0
	}

	@Test
	public void testCopyRemoteFileToLocal() {
		def filename = "date.${Config.myUser}"
		assert session.execute( "date > /tmp/$filename" ) == 0
		assert session.copyRemoteFiletoLocal("/tmp/$filename", "data/$filename") == 0
		def f = new File("data/$filename")
		assert f.exists()
		f.delete()
	}
	
	@Test
	public void testSetEnv() {
		session.context['VAR1'] = 'VALUE1'
		def output = new StringBuilder()
		assert session.execute( "env|grep VAR1", output ) == 0
		assert output.contains('VALUE1')
	}

	@Test
	public void testGetEnv() {
		session.context['VAR1'] = 'VALUE1'
		session.context['VAR2'] = ''
		def output = new StringBuilder()
		def script = 'CTX_VAR1=VALUE2; CTX_VAR2=192.168.1.1; export CTX_VAR3="new"; pwd'
		assert session.execute( script, output ) == 0
		assert session.context['VAR1'] == 'VALUE2'
		assert session.context['VAR2'] == '192.168.1.1'
		assert session.context['VAR3'] == 'new'
	}
}
