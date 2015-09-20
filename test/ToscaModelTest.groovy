package test;

import static org.junit.Assert.*

import org.junit.Test

import core.SSHScript
import core.ToscaModel

class ToscaModelTest {

	@Test
	public void wrong_filename() {
		try {
			ToscaModel m = new ToscaModel("this is impossible")
			fail "should have failed"
		}
		catch (Exception e) {
			// ok
			assert e.getMessage().contains("Le fichier spécifié est introuvable")||
				   e.getMessage().contains("The system cannot find the file specified")				
		}
	}

	@Test
	public void bad_Yaml_content() {
		try {
			// manque un ':' après my_server
			ToscaModel m = new ToscaModel("yaml/tosca_bad.yaml")
			fail "aurais du planter"
		}
		catch (Exception e) {
			// ok
			assert e.getMessage().contains("mapping values are not allowed here")
		}
	}
	
	@Test
	public void correct_Yaml_content() {
		ToscaModel m = new ToscaModel("yaml/tosca_simple.yaml")
		assert m.tosca_definitions_version == 'tosca_simple_yaml_1_0'
		assert m.decription.startsWith("TOSCA simple profile")
	}

	@Test
	public void inputs_cloudify() {
		ToscaModel m = new ToscaModel("yaml/cloudify_simple.yaml")
		assert m.inputs == [ "public_ip", "private_ip", "ssh_user", "ssh_key_filename", "agents_user", "resources_prefix" ]
		assert m.getInputType("ssh_user") == 'string'
		assert m.getInputDefault("agents_user") == 'ubuntu'
		try {
			assert m.getInputType("ceci est impossible") == 'string'
			fail "aurais du planter"
		}
		catch(Exception e) {
			// ok
		}
	}
	
	@Test
	public void inputs_tosca() {
		ToscaModel m = new ToscaModel("yaml/tosca_simple.yaml")
		assert m.inputs == [ "cpus" ]
		assert m.getInputType("cpus") == 'integer'
		assert m.getInputDescription("cpus").startsWith("Number of CPUs for the server.")
		assert m.getInputValidValues("cpus") == [ 1, 2, 4, 8 ]
	}

	@Test
	public void inputs_vide() {
		ToscaModel m = new ToscaModel("yaml/tosca_sans_input.yaml")
		assert m.inputs == null
	}
	
	@Test
	public void node_templates() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		assert m.getNodeTemplates() == [ "icpl", "weblogic_server", "install_scripts", "host", "oracle_server", "dilpd01b.dns21.socgen" ]
		assert m.getNodeTemplateType("icpl") == 'cloudify.nodes.ApplicationModule'
		assert m.getNodeTemplateRelationships("icpl") == [
			'cloudify.relationships.contained_in weblogic_server',
			'cloudify.relationships.depends_on install_scripts'
			]
	}
	
	@Test
	public void start_deployment_order() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		List depo = m.getDeploymentOrder('install')
		assert depo[0] == 'dilpd01b.dns21.socgen'
		assert depo[1] == 'oracle_server'
		assert depo[2] == 'host'
		assert depo[3] == 'install_scripts'
		assert depo[4] == 'weblogic_server'
		assert depo[5] == 'icpl'
	}
	
	@Test
	public void start_commands_1_node_template() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		def nt = m.getNodeTemplate("icpl")
		def verb = "start"
		assert nt.interfaces != null
		assert nt.interfaces."cloudify.interfaces.lifecycle" != null
		assert nt.interfaces."cloudify.interfaces.lifecycle"[verb] != null
		assert nt.interfaces."cloudify.interfaces.lifecycle"[verb].inputs != null
		assert nt.interfaces."cloudify.interfaces.lifecycle"[verb].inputs.commands[0].contains('install-icpl-app.sh')
	}

	
	@Test
	public void ssh_script_all() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		List depo = m.getDeploymentOrder('install')
		depo.each { nodeTemplateName ->
			SSHScript sshs = m.getSSHScript(nodeTemplateName, "start")
			println sshs
		}
	}
	
	@Test
	public void ssh_script_cloudify() {
		ToscaModel m = new ToscaModel("yaml/cloudify/cloudify_ssh.yaml")
		List depo = m.getDeploymentOrder('install')
		depo.each { nodeTemplateName ->
			SSHScript sshs = m.getSSHScript(nodeTemplateName, "start")
			if (sshs != null) {
				int rc = sshs.execute()
				assert rc == 0
			}
		}
	}

	@Test
	public void ssh_script_cloudify_with_context() {
		ToscaModel m = new ToscaModel("yaml/cloudify/cloudify_ssh_context.yaml")
		List depo = m.getDeploymentOrder('install')
		depo.each { nodeTemplateName ->
			SSHScript sshs = m.getSSHScript(nodeTemplateName, "start")
			if (sshs != null) {
				sshs.context['VAR1'] = 'VALEUR1'
				int rc = sshs.execute()
				assert rc == 0
			}
		}
	}
	
	@Test
	public void ssh_script_cloudify_all_scripts() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		List depo = m.getDeploymentOrder('install')
		List<String> result = []
		depo.each { nodeTemplateName ->
			SSHScript sshs = m.getSSHScript(nodeTemplateName, "start")
			if (sshs != null) { 
				assert sshs.toString().startsWith("ssh -i")
				result.add(sshs.toString()) 
			}
		}
		assert result.size() == 3
		assert result[0].contains("mkdir -p /tmp/icpl/icpl-scripts")
		assert result[0].contains("wget -O /tmp/icpl/icpl-scripts.tar")
		assert result[0].contains("chmod +x /tmp/icpl/icpl-scripts/icpl/*.sh")
		assert result[1].contains("/tmp/icpl/icpl-scripts/wls/install-wls.sh 7001")
		assert result[1].contains("python config_parser.py")
		assert result[1].contains("docker exec wlsicpl7001")
		assert result[2].contains("/tmp/icpl/icpl-scripts/icpl/install-icpl-app.sh")
	}
	
	@Test
	public void ssh_script_one_node_template() {
		ToscaModel m = new ToscaModel("yaml/cloudify/icpl/icpl-poc.yaml")
		SSHScript sshs = m.getSSHScript("icpl", "start")
		assert sshs != null
		assert sshs.identity_file == '/root/.ssh/id_rsa'
		assert sshs.user == 'pmaaadm'
		assert sshs.hostname == '192.88.64.26'
		println sshs
	}

	@Test
	public void check_relationships_fails() {
		try {
			ToscaModel m = new ToscaModel("yaml/bad_relationships.yaml")
			fail "aurais du planter"
		}
		catch(Exception e) {
			// ok
			assert e.getMessage().contains("n'existe pas dans le modèle")
		}
	}
}
