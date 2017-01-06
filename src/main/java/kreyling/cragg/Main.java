package kreyling.cragg;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.left;

import lombok.AllArgsConstructor;
import lombok.Value;
import lombok.experimental.NonFinal;
import ratpack.exec.util.ParallelBatch;
import ratpack.handling.Context;
import ratpack.http.MediaType;
import ratpack.http.Status;
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
import java.util.Objects;
import java.util.stream.Stream;

public class Main {

    public static final String JENKINS_API_SUFFIX = "/api/xml";

    public static final String CUCUMBER_REPORTS_PATH = "/cucumber-html-reports/";
    public static final String CUCUMBER_REPORTS_OVERVIEW_PAGE = CUCUMBER_REPORTS_PATH + "feature-overview.html";

    public static void main(String... args) throws Exception {
        String jenkinsJob = args[0];

        RatpackServer.start(server -> server
            .serverConfig(c -> c.baseDir(BaseDir.find()).build())
            .handlers(chain -> chain
                    .files(files -> files.dir("static"))
                    .get(context -> new JenkinsRequestProcessor(jenkinsJob, context).process())
            )
        );
    }

    @Value @NonFinal
    private static class TestReportLine {
        String feature;
        String featureLink;
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
            super(feature, "", "", "", "", "");
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
    @AllArgsConstructor
    private static class JenkinsRequestProcessor {
        String jenkinsJob;
        Context context;
        AggregatedReportBuilder aggregatedReportBuilder;

        public JenkinsRequestProcessor(String jenkinsJob, Context context) {
            this(jenkinsJob, context, new AggregatedReportBuilder(jenkinsJob));
        }

        public void process() {
            HttpClient httpClient = context.get(HttpClient.class);

            httpClient.get(URI.create(jenkinsJob + JENKINS_API_SUFFIX))
                .map(this::getTextFromResponseBody)
                .map(this::parseBuildNumbersFromJob)
                .then(builds -> ParallelBatch.of(builds.stream()
                    .map(build -> {
                        URI uri = URI.create(jenkinsJob + build + CUCUMBER_REPORTS_OVERVIEW_PAGE);
                        return httpClient.get(uri)
                            .map(this::getTextFromResponseBody)
                            .map(this::repairHtml)
                            .map(text -> parseTestReport(text, build));
                    })
                    .collect(toList()))
                    .yield()
                    .map(testReports -> testReports.stream().filter(Objects::nonNull).collect(toList()))
                    .then(this::renderTestReports)
                );

        }

        private String getTextFromResponseBody(ReceivedResponse receivedResponse) {
            return receivedResponse.getBody().getText();
        }

        private List<String> parseBuildNumbersFromJob(String text) {
            Document document = readDocument(text);
            XPathFactory xPathFactory = XPathFactory.instance();

            XPathExpression<Element> buildXPath = xPathFactory.compile("//build/number", Filters.element());

            String lastSuccessfulBuild = getSingleValue("//lastSuccessfulBuild/number", xPathFactory, document);
            String firstBuild = getSingleValue("//firstBuild/number", xPathFactory, document);

            return buildXPath.evaluate(document).stream()
                .map(Element::getText)
                .filter(build -> !build.equals(firstBuild))
                .filter(build -> !build.equals(lastSuccessfulBuild))
                .collect(toList());
        }

        private String getSingleValue(String query, XPathFactory xPathFactory, Document document) {
            XPathExpression<Element> xPathExpression = xPathFactory.compile(query, Filters.element());
            return xPathExpression.evaluate(document).get(0).getText();
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

        private TestReport parseTestReport(String text, String build) {
            Document document = readDocument(text);
            XPathFactory xPathFactory = XPathFactory.instance();

            try {
                if (text.contains("Not found")) return null;
                if (text.contains("You have no features in your cucumber report")) return null;

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
            } catch (RuntimeException e) {
                throw new RuntimeException(build + ": " + left(text, 200), e);
            }
        }

        private TestReportLine mapHtmlRowToTestReportLine(Element element) {
            return new TestReportLine(
                element.getChildren().get(0).getChildren().get(0).getText(),
                element.getChildren().get(0).getChildren().get(0).getAttributeValue("href"),
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

            context.render(aggregatedReportBuilder.buildHtml(testReports, aggregatedTestReportLines));
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

    @Value
    public static class AggregatedReportBuilder {
        String jenkinsJob;
        StringBuilder response = new StringBuilder();

        private String buildHtml(
            List<? extends TestReport> testReports,
            List<AggregatedTestReportLine> aggregatedTestReportLines
        ) {
            appendLine("<!DOCTYPE html>");
            appendLine("<html>");
            appendLine("<head>");
            appendLine("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />");
            appendLine("<link rel=\"stylesheet\" href=\"css/bootstrap.min.css\" type=\"text/css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/reporting.css\" type=\"text/css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/font-awesome.min.css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/progressbar.css\"/>");
            appendLine("</head>");
            appendLine("<body>");
            appendLine("<table class=\"stats-table table-hover\">");
            appendLine("<thead>");
            appendLine("<tr class=\"header dont-sort\">");
            appendLine("<th>Feature</th>");
            testReports.stream().sorted(comparing(TestReport::getBuildNumber)).forEach(testReport -> {
                append("<th>");
                append("<a href=\"").append(jenkinsJob).append(testReport.buildNumber).append("/\">");
                append(testReport.buildNumber);
                append("</a>");
                appendLine("</th>");
            });
            appendLine("</tr>");
            appendLine("</thead>");

            aggregatedTestReportLines.forEach(aggregatedTestReportLine -> {
                appendLine("<tr>");
                append("<td class=\"tagname\">").append(aggregatedTestReportLine.feature).appendLine("</td>");
                aggregatedTestReportLine
                    .getTestReportLinesWithBuildNumber()
                    .forEach(this::writeOneTestResult);
                appendLine("</tr>");
            });
            appendLine("</table>");
            appendLine("</body>");
            appendLine("</html>");

            return response.toString();
        }

        private void writeOneTestResult(TestReportLineWithBuildNumber testReportLineWithBuildNumber) {
            String buildNumber = testReportLineWithBuildNumber.buildNumber;
            String featureLink = testReportLineWithBuildNumber.testReportLine.featureLink;
            String status = testReportLineWithBuildNumber.testReportLine.status;
            int failedAndSkippedSteps = testReportLineWithBuildNumber.testReportLine.getFailedAndSkippedStepsInt();
            int totalSteps = testReportLineWithBuildNumber.testReportLine.getTotalStepsInt();

            append("<td class=\"");
            append(status.toLowerCase());
            append("\">");
            if (status.equals("Failed")) {
                append("<a href=\"");
                append(jenkinsJob).append(buildNumber).append(CUCUMBER_REPORTS_PATH).append(featureLink);
                append("\">");
                append("<span class=\"text-danger\">");
                append(failedAndSkippedSteps);
                append(" / ");
                append(totalSteps);
                append("</span>");
                append("</a>");
            } else if (status.equals("Passed")) {
                append("<a href=\"");
                append(jenkinsJob).append(buildNumber).append(CUCUMBER_REPORTS_PATH).append(featureLink);
                append("\">");
                append("<span class=\"glyphicon glyphicon-ok text-success\" aria-hidden=\"true\"></span>");
                append("</a>");
            }
            append("</td>");
            append("\n");
        }

        private void appendLine(String line) {
            response.append(line).append("\n");
        }

        private AggregatedReportBuilder append(String text) {
            response.append(text);
            return this;
        }

        private AggregatedReportBuilder append(int number) {
            response.append(number);
            return this;
        }
    }
}