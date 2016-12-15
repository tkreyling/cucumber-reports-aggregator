package kreyling.cragg;

import static java.util.stream.Collectors.joining;
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

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import java.io.StringReader;
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
                    .map(this::repairHtml)
                    .map(this::parseTestReport)
                )
                .collect(toList()))
                .yield()
                .then(this::renderTestReports);
        }

        private String repairHtml(String text) {
            return Stream.of(text.split("\n"))
                .map(this::replaceBrokenLine)
                .collect(joining("\n"));

        }

        private String replaceBrokenLine(String line) {
            if (line.contains("container-fluid>")) {
                return "<div id=\"report-lead\" class=\"container-fluid\">";
            } else if (line.contains("role=\"button\" data-slide=\"prev\"></span>")) {
                return "<a class=\"left carousel-control\" href=\"#featureChartCarousel\" role=\"button\" data-slide=\"prev\">";
            } else if (line.contains("<br>")) {
                return "<br/>";
            } else {
                return line;
            }
        }

        private TestReport parseTestReport(String text) {
            Document document = readDocument(text);
            XPathFactory xPathFactory = XPathFactory.instance();

            XPathExpression<Element> xPathExpression = xPathFactory.compile(
                "//td[@class=\"tagname\"]/..", Filters.element());

            List<Element> elements = xPathExpression.evaluate(document);

            return new TestReport(
                elements.stream()
                    .map(this::mapHtmlRowToTestReportLine)
                    .collect(toList())
            );
        }

        private TestReportLine mapHtmlRowToTestReportLine(Element element) {
            return new TestReportLine(
                element.getChildren().get(0).getChildren().get(0).getText(),
                element.getChildren().get(11).getText()
            );
        }

        private Document readDocument(String text) {
            try {
                return new SAXBuilder().build(new StringReader(text));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void renderTestReports(List<? extends TestReport> testReports) {
            context.getResponse().status(Status.OK).contentType(MediaType.TEXT_HTML);
            context.render(testReports.get(0).getTestReportLines().get(0).toString());
        }

    }
}