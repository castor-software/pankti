package se.kth.castor.pankti.generate;

import picocli.CommandLine;
import se.kth.castor.pankti.generate.generators.TestGenerator;
import se.kth.castor.pankti.generate.serializers.Serializer;
import spoon.MavenLauncher;
import spoon.reflect.CtModel;
import spoon.support.compiler.SpoonPom;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "java -jar target/<pankti-gen-version-jar-with-dependencies.jar>",
        description = "pankti-gen generates test cases from serialized objects",
        usageHelpWidth = 200)
public class PanktiGenMain implements Callable<Integer> {
    @CommandLine.Parameters(
            index = "0",
            paramLabel = "PATH",
            description = "Path of the Maven project")
    private Path projectPath;

    @CommandLine.Parameters(
            index = "1",
            paramLabel = "CSV_FILE",
            description = "Path to CSV file with invoked methods")
    private Path methodCSVFilePath;

    @CommandLine.Parameters(
            index = "2",
            paramLabel = "DIRECTORY_WITH_OBJECT_XML_FILES",
            description = "Path to directory containing object XML files")
    private Path objectXMLDirectoryPath;

    @CommandLine.Option(
            names = {"-d", "--serializer"},
            defaultValue = "xstream",
            paramLabel = "SERIALIZER",
            description = "Specify the format that is used in the generated tests, " +
                    "default: ${DEFAULT-VALUE}, candidates values: ${COMPLETION-CANDIDATES}")
    private Serializer serializer;

    @CommandLine.Option(
            names = {"-h", "--help"},
            description = "Display help/usage.",
            usageHelp = true)
    private boolean usageHelpRequested;

    public PanktiGenMain() {}

    public Path getProjectPath() {
        return projectPath;
    }

    public Integer call() {
        if (usageHelpRequested) {
            return 1;
        }
        final String path = this.projectPath.toString();
        final String name = this.projectPath.getFileName().toString();
        PanktiGenLauncher panktiGenLauncher = new PanktiGenLauncher();
        MavenLauncher launcher = panktiGenLauncher.getMavenLauncher(path, name);
        SpoonPom projectPom = launcher.getPomFile();

        CtModel model = panktiGenLauncher.buildSpoonModel(launcher);
        System.out.println("POM found at: " + projectPom.getPath());
        System.out.println("Number of Maven modules: " + projectPom.getModel().getModules().size());

        TestGenerator testGenerator = new TestGenerator(this.serializer);
        System.out.println("Number of new test cases: " + testGenerator.process(model, launcher,
                methodCSVFilePath.toString(), objectXMLDirectoryPath.toString()));

        // Save model in outputdir/

        String outputDirectory = "./output/generated/" + name;
        launcher.setSourceOutputDirectory(outputDirectory);
        launcher.prettyprint();
        return 0;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new PanktiGenMain()).execute(args);
        System.exit(exitCode);
    }
}
