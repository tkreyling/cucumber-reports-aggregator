package kreyling.cragg;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import kreyling.cragg.Main.Build;
import kreyling.cragg.Main.JenkinsRequestProcessor;

import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.junit.Test;

import java.util.Optional;

public class ParseBuildInformationTest {
    JenkinsRequestProcessor jenkinsRequestProcessor = new JenkinsRequestProcessor(null, null, null);

    @Test
    public void parseGivenStartedByUser() {
        Document startedByUser = readTestDocument("startedByUser.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByUser, "testrun");

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.of("Kreyling, Thomas")));
    }

    @Test
    public void handleNotGivenStartedByUser() {
        Document startedByUser = readTestDocument("startedByJob.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByUser, "testrun");

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.empty()));
    }

    private Document readTestDocument(String filename) {
        try {
            return new SAXBuilder().build(getClass().getResourceAsStream("/buildinformation/" + filename));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
