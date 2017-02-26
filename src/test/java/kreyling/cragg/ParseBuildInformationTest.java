package kreyling.cragg;

import static java.util.Collections.emptyList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import kreyling.cragg.Main.Build;
import kreyling.cragg.Main.BuildReference;
import kreyling.cragg.Main.JenkinsRequestProcessor;

import org.jdom2.Document;
import org.jdom2.input.SAXBuilder;
import org.junit.Test;

import java.util.Optional;

public class ParseBuildInformationTest {
    JenkinsRequestProcessor jenkinsRequestProcessor = new JenkinsRequestProcessor(null, null, null, null);
    BuildReference testBuildReference = new BuildReference("testrun", null);

    @Test
    public void startedByUser() {
        Document startedByUser = readTestDocument("startedByUser.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByUser, testBuildReference);

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.of("Kreyling, Thomas")));
        assertThat(build.upstreamBuildReferences, is(emptyList()));
        assertThat(build.scmChanges.size(), is(0));
    }

    @Test
    public void startedByJob() {
        Document startedByUser = readTestDocument("startedByJob.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByUser, testBuildReference);

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.empty()));
        assertThat(build.upstreamBuildReferences.get(0).number, is("1518"));
        assertThat(build.upstreamBuildReferences.get(0).jobPath, is("job/some-other-project/"));
        assertThat(build.scmChanges.size(), is(0));
    }

    @Test
    public void startedByTwoDifferentJobs() {
        Document startedByTwoDifferentJobs = readTestDocument("startedByTwoDifferentJobs.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByTwoDifferentJobs, testBuildReference);

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.empty()));
        assertThat(build.upstreamBuildReferences.size(), is(2));
        assertThat(build.upstreamBuildReferences.get(0).number, is("13"));
        assertThat(build.upstreamBuildReferences.get(0).jobPath, is("job/other-project-1/"));
        assertThat(build.upstreamBuildReferences.get(1).number, is("985"));
        assertThat(build.upstreamBuildReferences.get(1).jobPath, is("job/other-project-2/"));
        assertThat(build.scmChanges.size(), is(0));
    }

    @Test
    public void startedByScmChange() {
        Document startedByScmChange = readTestDocument("startedByScmChange.xml");

        Build build = jenkinsRequestProcessor.parseBuildInfo(startedByScmChange, testBuildReference);

        assertThat(build.buildNumber, is("testrun"));
        assertThat(build.startedByUser, is(Optional.empty()));
        assertThat(build.upstreamBuildReferences, is(emptyList()));

        assertThat(build.scmChanges.size(), is(2));
        assertThat(build.scmChanges.get(0).user, is("Mustermann, Max"));
        assertThat(build.scmChanges.get(0).comment, is(
            "ABCD-3656 Überschrift des Kommentars\n" +
                "                - Erste Zeile\n" +
                "                - Längere zweite Zeile (mit Kram hinten dran)\n" +
                "            "));
    }

    private Document readTestDocument(String filename) {
        try {
            return new SAXBuilder().build(getClass().getResourceAsStream("/buildinformation/" + filename));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
