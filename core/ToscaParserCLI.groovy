package core

def cli = new CliBuilder(usage: 'ToscaParserCLI -b blueprint.yaml',
	header: '\nAvailable options (use -h for help):\n')
// Create the list of options.
cli.with {
	b longOpt: 'blueprint', 'TOSCA yaml blueprint', required: true
	h longOpt: 'help', 'Show usage information', required: false
}

def options = cli.parse(args)
if (!options) {
	println ""
	return
}
// Show usage text when -h or --help option is used.
if (options.h) {
	cli.usage()
	return
}
