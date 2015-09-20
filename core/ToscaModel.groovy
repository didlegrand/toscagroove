package core

import java.util.Stack;

import org.junit.After;
import org.yaml.snakeyaml.Yaml

class ToscaModel {
	
	def model
	
	public ToscaModel(String yaml_filename) {
		String script = new File(yaml_filename).text.
			replaceAll('@@myHost@@',Config.getMyHost()).
			replaceAll('@@myHost2@@',Config.getMyHost2()).
			replaceAll('@@myUser@@',Config.getMyUser()).
			replaceAll('@@myIdentityFile@@',Config.getMyIdentityFile())
			
		model = new Yaml().load(script)
		check_relationships()
	}
	
	void check_relationships() {
		model.node_templates.each { nt_name, nt ->
			nt.relationships.each { r ->
				if (model.node_templates[r.target] == null) {
					throw new Exception("la cible $r.target dans la relatiob $r.type de $nt_name n'existe pas dans le modèle")
				}
			}
		}
	}
	
	String getTosca_definitions_version() {
		model.tosca_definitions_version
	}
	
	String getDecription() {
		model.description
	}
	
	List<String> getInputs() {
		return model.inputs*.getKey()
	}
	
	private def getInput(String inputName) {
		def i = model.inputs[inputName]
		if (i == null) {
			throw new Exception("$inputName n'est pas un nom d'input")
		}
		return i
	}
	
	String getInputType(String inputName) {
		getInput(inputName).type
	}	
	
	String getInputDefault(String inputName) {
		getInput(inputName).default
	}
	
	String getInputDescription(String inputName) {
		getInput(inputName).description
	}
	
	List<String> getInputValidValues(String inputName) {
		getInput(inputName).constraints[0].valid_values
	}
	
	List<String> getNodeTemplates() {
		return model.node_templates*.getKey()
	}
	
	private def getNodeTemplate(String nodeTemplateName) {
		def nt = model.node_templates[nodeTemplateName]
		if (nt == null) {
			throw new Exception("$nodeTemplateName n'est pas un nom de node_template")
		}
		return nt
	}
	
	String getNodeTemplateType(String ntName) {
		getNodeTemplate(ntName).type
	}
	
	List<String> getNodeTemplateRelationships(String ntName) {
		def result = []
		getNodeTemplate(ntName).relationships.each { 
			result << "$it.type $it.target"
		}
		return result
	}
	
	/**
	 * Returns the list of node templates to be processed for a given work flow,
	 * ordered according the relationships between the nodes.
	 * @param workflow = install
	 * @return
	 */
	List<String> getDeploymentOrder(String workflow) {
		if (workflow != 'install') {
			throw new Exception("seul le workflow install est géré pour le moment")
		}
		def children = [:]
		def parents = [:]
		getNodeTemplates().each { 
			children[it] = []
			parents[it] = []
		}
		// recherche les dépendances du node template vers ses parents (= cibles de ses relations)
		model.node_templates.each { String ntName, nt ->
			nt.relationships.each {
				def parent = it.target
				def child = ntName
				if (!children[parent].contains(child)) { children[parent].add(child) }
				if (!parents[child].contains(parent)) { parents[child].add(parent) }
			}
		}
		// traite les enfants en commençant par les noeuds de plus haut niveau (sans parent)
		List todo = []
		parents.each { node, parents_list ->
			if (parents_list.size() == 0) {
				process(children, node, todo)
			}
		}
		return todo
	}
	
	// recursively builds the todo list, traversing the children tree depth-first
	static private void process(Map children, String nt, List todo) {
		children[nt].each { child ->
			process(children, child, todo)
		}
		if (!todo.contains(nt)) {
			todo.add(0, nt)
		}
	}
	
	/**
	 * Builds the ssh script associated with a lifecycle verb
	 * with Cloudify lifecycle + Fabric plugin 
	 * interfaces:
	 *   cloudify.interfaces.lifecycle:
	 *     <verb>:
	 *       implementation: fabric.fabric_plugin.tasks.run_commands
	 *       inputs:
	 *         commands:
	 *         - <ssh command #1>
	 *         - <ssh command #2>
	 *         ...
	 *         fabric_env:
	 *           host_string: <host>
	 *           user: <user>
	 *           key-filename: <identity_file>
	 */
	SSHScript getSSHScriptCloudifyFabric(String nodeTemplateName, String verb) {
		def nt = getNodeTemplate(nodeTemplateName)
		if ((nt.interfaces."cloudify.interfaces.lifecycle"[verb].inputs == null) ||
		   (nt.interfaces?."cloudify.interfaces.lifecycle"[verb].inputs.commands == null)) { return null }
		def _hostname = nt.interfaces?."cloudify.interfaces.lifecycle"[verb].inputs.fabric_env.host_string
		assert _hostname != null
		def _user = nt.interfaces?."cloudify.interfaces.lifecycle"[verb].inputs.fabric_env.user
		assert _user != null
		def _identity_file = nt.interfaces?."cloudify.interfaces.lifecycle"[verb].inputs.fabric_env.key_filename
		assert _identity_file != null
		StringBuilder sb = new StringBuilder()
		
		nt.interfaces."cloudify.interfaces.lifecycle"[verb].inputs.commands.each { cmd ->
			if (cmd != null) {
				sb.append("$cmd\n")
			}
		}
		return new SSHScript(identity_file:_identity_file, hostname:_hostname, port:22, user:_user, script:sb.toString())
	}

	/**
	 * Builds the ssh script associated with a lifecycle verb
	 * @param nodeTemplateName
	 * @param verb
	 * @return
	 */
	SSHScript getSSHScript(String nodeTemplateName, String verb) {
		def nt = getNodeTemplate(nodeTemplateName)
		if (nt.interfaces == null) { return null } // no interface
		if (nt.interfaces."cloudify.interfaces.lifecycle" != null) {
			if (nt.interfaces."cloudify.interfaces.lifecycle"[verb] == null) { return null } // no implementation
			if (nt.interfaces."cloudify.interfaces.lifecycle"[verb].implementation == 'fabric.fabric_plugin.tasks.run_commands') {
				return getSSHScriptCloudifyFabric(nodeTemplateName, verb)
			}			
		}
		throw new Exception("Seuls les scripts exécutés avec un lifecycle Cloudify + plugin Fabric sont supportés")
	}

}
