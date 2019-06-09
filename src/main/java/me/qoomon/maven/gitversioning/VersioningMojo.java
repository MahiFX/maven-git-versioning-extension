package me.qoomon.maven.gitversioning;

import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;

import static me.qoomon.maven.gitversioning.MavenUtil.*;

import de.pdark.decentxml.Document;
import de.pdark.decentxml.Element;
import de.pdark.decentxml.XMLParser;
import de.pdark.decentxml.XMLStringSource;

/**
 * Temporarily replace original pom files with pom files generated from in memory project models.
 * <p>
 * !!! DO NOT ADD THIS PLUGIN MANUALLY TO POM !!!
 * <p>
 * utilized by {@link ModelProcessor}
 */

@Mojo(name = VersioningMojo.GOAL,
      defaultPhase = LifecyclePhase.PROCESS_RESOURCES,
      threadSafe = true)
public class VersioningMojo extends AbstractMojo {

    static final String GOAL = "git-versioning";
    static final String GIT_VERSIONING_POM_NAME = ".git-versioned-pom.xml";
    static final String propertyKeyPrefix = VersioningMojo.class.getName() + ".";
    static final String propertyKeyUpdatePom = "updatePom";
    static final String propertyKeyForceUpdateParent = "forceUpdateParent";


    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public synchronized void execute() throws MojoExecutionException {
        try {
            // read plugin properties
            final boolean configUpdatePom = Boolean.valueOf(
                    project.getProperties().getProperty(propertyKeyPrefix + propertyKeyUpdatePom));

            // read plugin properties
            final boolean forceUpdateParent = Boolean.valueOf(
                    project.getProperties().getProperty(propertyKeyPrefix + propertyKeyForceUpdateParent));

            // remove plugin and properties
            getLog().debug(project.getModel().getArtifactId() + "remove this plugin and plugin properties from model");
            Model originalModel = project.getOriginalModel();
            originalModel.getBuild().removePlugin(asPlugin());
            new HashSet<>(originalModel.getProperties().keySet()).forEach(key -> {
                if (((String) key).startsWith(propertyKeyPrefix)) {
                    originalModel.getProperties().remove(key);
                }
            });

            getLog().info("Generating git versioned POM of project " + GAV.of(originalModel));

            File pomFile = project.getFile();

            Document gitVersionedPomDocument = readXml(pomFile);
            Element versionElement = gitVersionedPomDocument.getChild("/project/version");
            if (versionElement != null) {
                versionElement.setText(project.getVersion());
            }
            Element parentVersionElement = gitVersionedPomDocument.getChild("/project/parent/version");
            if (parentVersionElement != null /*&& isProjectPom(project.getParent().getFile()*/) {
                parentVersionElement.setText(project.getVersion());
            }

            File gitVersionedPomFile = new File(project.getBuild().getDirectory(), GIT_VERSIONING_POM_NAME);
            Files.createDirectories(gitVersionedPomFile.getParentFile().toPath());
            writeXml(gitVersionedPomFile, gitVersionedPomDocument);

            // replace pom file with git-versioned pom file within current session
            project.setPomFile(gitVersionedPomFile);

            if (configUpdatePom) {
                getLog().info("Updating original POM");
                Files.copy(gitVersionedPomFile.toPath(), pomFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Git Versioning Pom Replacement Mojo", e);
        }
    }

    private static File writeXml(final File file, final Document gitVersionedPom) throws IOException {
        Files.write(file.toPath(), gitVersionedPom.toXML().getBytes());
        return file;
    }

    private static Document readXml(File file) throws IOException {
        String pomXml = new String(Files.readAllBytes(file.toPath()));
        XMLParser parser = new XMLParser();
        return parser.parse(new XMLStringSource(pomXml));
    }

    static Plugin asPlugin() {
        Plugin plugin = new Plugin();
        plugin.setGroupId(BuildProperties.projectGroupId());
        plugin.setArtifactId(BuildProperties.projectArtifactId());
        plugin.setVersion(BuildProperties.projectVersion());
        return plugin;
    }
}
