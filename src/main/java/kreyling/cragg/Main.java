package kreyling.cragg;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

import lombok.Value;
import lombok.experimental.NonFinal;
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
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;

import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Map;
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

    @Value @NonFinal
    private static class TestReportLine {
        String feature;
        String failedSteps;
        String skippedSteps;
        String totalSteps;
        String status;

        public int toInt(String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        public int getTotalStepsInt() {
            return toInt(totalSteps);
        }

        public int getFailedStepsInt() {
            return toInt(failedSteps);
        }

        public int getSkippedStepsInt() {
            return toInt(skippedSteps);
        }

        public int getFailedAndSkippedStepsInt() {
            return getFailedStepsInt() + getSkippedStepsInt();
        }
    }

    private static class NullTestReportLine extends TestReportLine{
        public NullTestReportLine(String feature) {
            super(feature, "", "", "", "");
        }
    }

    @Value
    private static class TestReport {
        public String buildNumber;
        Map<String, List<TestReportLine>> testReportLinesByFeature;

        public TestReport(String buildNumber, List<TestReportLine> testReportLines) {
            this.buildNumber = buildNumber;
            testReportLinesByFeature = testReportLines.stream().collect(groupingBy(TestReportLine::getFeature));
        }

        public Stream<String> getAllFeatures() {
            return testReportLinesByFeature.keySet().stream();
        }

        public TestReportLine getTestReportLineByFeature(String feature) {
            if (!testReportLinesByFeature.containsKey(feature)) return new NullTestReportLine(feature);

            return testReportLinesByFeature.get(feature).get(0);
        }
    }

    @Value
    private static class AggregatedTestReportLine {
        String feature;
        List<TestReportLineWithBuildNumber> testReportLinesWithBuildNumber;
    }

    @Value
    private static class TestReportLineWithBuildNumber {
        TestReportLine testReportLine;
        String buildNumber;
    }

    @Value
    private static class JenkinsRequestProcessor {
        Context context;

        public void process() {
            HttpClient httpClient = context.get(HttpClient.class);

            ParallelBatch.of(Stream.of(1322, 1321, 1319, 1318, 1317, 1316, 1315)
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

            XPathExpression<Element> titleXPath = xPathFactory.compile(
                "//title", Filters.element());

            Element title = titleXPath.evaluate(document).get(0);

            XPathExpression<Element> rowXPath = xPathFactory.compile(
                "//td[@class=\"tagname\"]/..", Filters.element());

            List<Element> rows = rowXPath.evaluate(document);

            return new TestReport(
                StringUtils.substringBetween(title.getText(), "(no ", ")"),
                rows.stream()
                    .map(this::mapHtmlRowToTestReportLine)
                    .collect(toList())
            );
        }

        private TestReportLine mapHtmlRowToTestReportLine(Element element) {
            return new TestReportLine(
                element.getChildren().get(0).getChildren().get(0).getText(),
                element.getChildren().get(6).getText(),
                element.getChildren().get(7).getText(),
                element.getChildren().get(4).getText(),
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

            List<AggregatedTestReportLine> aggregatedTestReportLines = testReports.stream()
                .flatMap(TestReport::getAllFeatures)
                .distinct()
                .map(feature -> createAggregatedTestReportLine(testReports, feature))
                .sorted(comparing(AggregatedTestReportLine::getFeature))
                .collect(toList());

            StringBuilder response = buildHtml(testReports, aggregatedTestReportLines);

            context.render(response.toString());
        }

        private StringBuilder buildHtml(
            List<? extends TestReport> testReports,
            List<AggregatedTestReportLine> aggregatedTestReportLines
        ) {
            StringBuilder response = new StringBuilder();
            appendLine(response, "<!DOCTYPE html>");
            appendLine(response, "<html>");
            appendLine(response, "<head>");
            appendLine(response, "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />");
            appendLine(response, "<link rel=\"stylesheet\" href=\"css/bootstrap.min.css\" type=\"text/css\"/>");
            appendLine(response, "<link rel=\"stylesheet\" href=\"css/reporting.css\" type=\"text/css\"/>");
            appendLine(response, "<link rel=\"stylesheet\" href=\"css/font-awesome.min.css\"/>");
            appendLine(response, "<link rel=\"stylesheet\" href=\"css/progressbar.css\"/>");
            appendLine(response, "</head>");
            appendLine(response, "<body>");
            appendLine(response, "<table class=\"stats-table table-hover\">");
            appendLine(response, "<thead>");
            appendLine(response, "<tr class=\"header dont-sort\">");
            appendLine(response, "<th>Feature</th>");
            testReports.stream().sorted(comparing(TestReport::getBuildNumber)).forEach(testReport ->
                response.append("<th colspan=\"2\">").append(testReport.buildNumber).append("</th>\n")
            );
            appendLine(response, "</tr>");
            appendLine(response, "</thead>");

            aggregatedTestReportLines.forEach(aggregatedTestReportLine -> {
                appendLine(response, "<tr>");
                response.append("<td class=\"tagname\">").append(aggregatedTestReportLine.feature).append("</td>\n");
                aggregatedTestReportLine.getTestReportLinesWithBuildNumber().forEach(testReportLineWithBuildNumber -> {
                        String status = testReportLineWithBuildNumber.testReportLine.status;
                        int failedAndSkippedSteps = testReportLineWithBuildNumber.testReportLine.getFailedAndSkippedStepsInt();
                        int totalSteps = testReportLineWithBuildNumber.testReportLine.getTotalStepsInt();
                        response
                            .append("<td class=\"")
                            .append(status.toLowerCase())
                            .append("\">");
                        if (status.equals("Failed")) {
                            response
                                .append(failedAndSkippedSteps)
                                .append(" / ")
                                .append(totalSteps)
                                .append(" ");
                        }
                        response
                            .append(status.toLowerCase())
                            .append("</td>");
                        response
                            .append("<td class=\"")
                            .append(status.toLowerCase())
                            .append("\">");
                        if (status.equals("Failed")) {
                            response
                                .append("<div class=\"progress center-block\">")
                                .append("<div class=\"progress-bar progress-bar-danger\"  role=\"progressbar\"  style=\"width: ")
                                .append(Math.min(100, Math.round((100.0 * failedAndSkippedSteps) / totalSteps)))
                                .append("%\"></div>")
                                .append("</div>");
                        }
                        response
                            .append("</td>")
                            .append("\n");
                    }
                );
                appendLine(response, "</tr>");
            });
            appendLine(response, "</table>");
            appendLine(response, "</body>");
            appendLine(response, "</html>");
            return response;
        }

        private void appendLine(StringBuilder response, String line) {
            response.append(line).append("\n");
        }

        private AggregatedTestReportLine createAggregatedTestReportLine(
            List<? extends TestReport> testReports,
            String feature
        ) {
            List<TestReportLineWithBuildNumber> allTestReportLinesForThisFeature = testReports.stream()
                .map(testReport -> new TestReportLineWithBuildNumber(
                    testReport.getTestReportLineByFeature(feature),
                    testReport.buildNumber))
                .sorted(comparing(TestReportLineWithBuildNumber::getBuildNumber))
                .collect(toList());

            return new AggregatedTestReportLine(feature, allTestReportLinesForThisFeature);
        }

    }
}