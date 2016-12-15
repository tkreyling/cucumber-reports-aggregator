package kreyling.cragg;

import static java.util.stream.Collectors.toList;

import lombok.Value;
import ratpack.exec.util.ParallelBatch;
import ratpack.handling.Context;
import ratpack.http.MediaType;
import ratpack.http.Status;
import ratpack.http.TypedData;
import ratpack.http.client.HttpClient;
import ratpack.http.client.ReceivedResponse;
import ratpack.server.BaseDir;
import ratpack.server.RatpackServer;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

public class Main {

    public static final String JENKINS_JOB = "https://jenkins.easycredit.intern/view/KW-B2B/view/kwb2b/job/kwb2b-tests/";
    public static final String CUCUMBER_REPORT = "/cucumber-html-reports/feature-overview.html";

    public static void main(String... args) throws Exception {
        RatpackServer.start(server -> server
            .serverConfig(c -> c.baseDir(BaseDir.find()).build())
            .handlers(chain -> chain
                    .files(files -> files.dir("static"))
                    .get(context -> new JenkinsRequestProcessor(context).process())
            )
        );
    }

    @Value
    private static class TestReportLine {
        String feature;
        String status;
    }

    @Value
    private static class TestReport {
        List<TestReportLine> testReportLines;
    }

    @Value
    private static class JenkinsRequestProcessor {
        Context context;

        public void process() {
            HttpClient httpClient = context.get(HttpClient.class);

            ParallelBatch.of(Stream.of(1322, 1321, 1319)
                .map(build -> URI.create(JENKINS_JOB + build + CUCUMBER_REPORT))
                .map(httpClient::get)
                .map(promise -> promise
                    .map(ReceivedResponse::getBody)
                    .map(TypedData::getText)
                    .map(this::parseTestReport)
                )
                .collect(toList()))
                .yield()
                .then(this::renderTestReports);
        }

        private TestReport parseTestReport(String text) {
            return new TestReport(
                Stream.of(text.split("\n"))
                    .filter(line -> line.contains("<td class=\"tagname\">"))
                    .map(line -> StringUtils.substringBetween(line, ">", "</"))
                    .map(feature -> new TestReportLine(feature, null))
                    .collect(toList())
            );
        }

        private void renderTestReports(List<? extends TestReport> testReports) {
            String testReport = testReports.get(0).getTestReportLines().get(0).feature;
            context.getResponse().status(Status.OK).contentType(MediaType.TEXT_HTML);
            context.render(testReport);
        }

    }
}