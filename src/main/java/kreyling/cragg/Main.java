package kreyling.cragg;

import static java.util.Collections.emptyList;
import static java.util.Comparator.comparing;
import static java.util.Optional.empty;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.left;
import static org.apache.commons.lang3.StringUtils.removeEnd;
import static org.apache.commons.lang3.StringUtils.removeStart;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.experimental.NonFinal;
import lombok.experimental.Wither;
import ratpack.exec.Promise;
import ratpack.exec.util.ParallelBatch;
import ratpack.func.Pair;
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
import org.joda.time.DateTime;
import org.joda.time.Duration;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public class Main {
    private static Logger logger = LoggerFactory.getLogger(Main.class);

    public static final String JENKINS_API_SUFFIX = "/api/xml";

    public static final String CUCUMBER_REPORTS_PATH = "/cucumber-html-reports/";
    public static final String CUCUMBER_REPORTS_OVERVIEW_PAGE = CUCUMBER_REPORTS_PATH + "feature-overview.html";

    public static void main(String... args) throws Exception {
        String host = args[0];
        String jenkinsJob = args[1];
        Optional<String> scmRepositoryBaseUrl = getScmRepositoryBaseUrl(args);

        logger.info("Starting ...");

        RatpackServer.start(server -> server
            .serverConfig(c -> c.baseDir(BaseDir.find()).build())
            .handlers(chain -> chain
                    .files(files -> files.dir("static"))
                    .get(context -> new JenkinsRequestProcessor(host, jenkinsJob, scmRepositoryBaseUrl, context, context.get(HttpClient.class))
                        .process()
//                        .queryJenkinsBuildInformationIncludingUpstreamBuild("1494")
//                        .map(build -> new BuildAndUpstreamBuild(build, Optional.empty()))
//                    .then(buildAndUpstreamBuild -> context.render(buildAndUpstreamBuild.toString()))
                        )
            )
        );
    }

    private static Optional<String> getScmRepositoryBaseUrl(String[] args) {
        if (args.length <= 2) return empty();
        return Optional.of(args[2]);
    }

    @Value
    @EqualsAndHashCode(of = "name")
    private static class Feature implements Comparable<Feature> {
        String name;
        String link;

        @Override
        public int compareTo(Feature otherFeature) {
            return name.compareTo(otherFeature.name);
        }
    }

    @Value @NonFinal
    private static class TestReportLine {
        Feature feature;
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
        public NullTestReportLine(Feature feature) {
            super(feature, "", "", "", "");
        }
    }

    @Value @NonFinal
    static class Build {
        public BuildReference buildReference;
        public Duration duration;
        public DateTime startedAt;
        public Optional<String> startedByUser;
        public List<BuildReference> upstreamBuildReferences;
        @Wither public List<? extends Build> upstreamBuilds;
        public List<ScmChange> scmChanges;

        public String getDurationFormatted() {
            PeriodFormatter minutesAndSeconds = new PeriodFormatterBuilder()
                .printZeroAlways()
                .appendHours()
                .appendSeparator(":")
                .minimumPrintedDigits(2)
                .appendMinutes()
                .appendSeparator(":")
                .minimumPrintedDigits(2)
                .appendSeconds()
                .toFormatter();
            return minutesAndSeconds.print(duration.toPeriod());
        }

        public String getStartedAtDateFormatted() {
            return DateTimeFormat.forPattern("dd.MM.").print(startedAt);
        }

        public String getStartedAtTimeFormatted() {
            return DateTimeFormat.forPattern("HH:mm").print(startedAt);
        }
    }

    static class NullBuild extends Build {
        public NullBuild(BuildReference buildReference) {
            super(buildReference, null, null, empty(), emptyList(), emptyList(), emptyList());
        }
    }

    @Value
    static class BuildReference {
        public String number;
        public String jobPath;

        public String getPlainJobName() {
            String result = removeStart(jobPath, "job/");
            return removeEnd(result, "/");
        }
    }

    @Value
    static class ScmChange {
        public String commitId;
        public String user;
        public String comment;
    }

    @Value
    static class TestReport {
        String buildNumber;
        List<TestReportLine> testReportLines;
        Map<Feature, List<TestReportLine>> testReportLinesByFeature;

        public TestReport(String buildNumber, List<TestReportLine> testReportLines) {
            this.buildNumber = buildNumber;
            this.testReportLines = testReportLines;
            testReportLinesByFeature = testReportLines.stream().collect(groupingBy(TestReportLine::getFeature));
        }

        public Stream<Feature> getAllFeatures() {
            return testReportLinesByFeature.keySet().stream();
        }

        public TestReportLine getTestReportLineByFeature(Feature feature) {
            if (!testReportLinesByFeature.containsKey(feature)) return new NullTestReportLine(feature);

            return testReportLinesByFeature.get(feature).get(0);
        }

        public boolean isSystemFailure() {
            double numberOfFeatures = testReportLines.size();

            List<String> sortedStatus = testReportLines.stream()
                .sorted(comparing(TestReportLine::getFeature))
                .map(TestReportLine::getStatus)
                .collect(toList());

            double directlySuccessionalFailures = countDirectlySuccessionalFailures(sortedStatus);

            return directlySuccessionalFailures / numberOfFeatures  > 0.15;
        }

        static int countDirectlySuccessionalFailures(List<String> sortedStatus) {
            int directlySuccessionalFailures = 0;
            int maxDirectlySuccessionalFailures = 0;

            for (String status : sortedStatus) {
                if (status.equals("Failed")) {
                    directlySuccessionalFailures ++;

                    if (directlySuccessionalFailures >= maxDirectlySuccessionalFailures) {
                        maxDirectlySuccessionalFailures = directlySuccessionalFailures;
                    }
                } else {
                    directlySuccessionalFailures = 0;
                }
            }

            return maxDirectlySuccessionalFailures;
        }
    }

    @Value
    private static class AggregatedTestReportLine {
        Feature feature;
        List<Pair<TestReportLine, TestReport>> testReportLinesAndTestReport;
    }

    @Value
    @AllArgsConstructor
    static class JenkinsRequestProcessor {
        String host;
        String jenkinsJob;
        Context context;
        AggregatedReportBuilder aggregatedReportBuilder;
        HttpClient httpClient;

        public JenkinsRequestProcessor(String host, String jenkinsJob, Optional<String> scmRepositoryBaseUrl,
            Context context, HttpClient httpClient) {
            this(
                host,
                jenkinsJob,
                context,
                new AggregatedReportBuilder(host, jenkinsJob, scmRepositoryBaseUrl),
                httpClient
            );
        }

        public void process() {
            queryJenkinsJobPage()
                .flatMap(buildReferences ->
                    ParallelBatch.of(
                        buildReferences.stream()
                            .map(buildReference ->
                                queryCucumberReport(buildReference)
                                    .left(queryJenkinsBuildInformationIncludingUpstreamBuild(buildReference)))
                            .collect(toList())
                    )
                        .yield()
                        .map(this::filterEmptyReports)
                )
                .then(this::renderTestReports);
        }

        private <T> List<? extends Pair<T, TestReport>> filterEmptyReports(List<? extends Pair<T, TestReport>> pairs) {
            return pairs.stream()
                .filter(pair -> pair.getRight() != null)
                .collect(toList());
        }

        private Promise<List<BuildReference>> queryJenkinsJobPage() {
            return httpGet(host + jenkinsJob + JENKINS_API_SUFFIX)
                .map(this::getTextFromResponseBody)
                .map(this::parseBuildNumbersFromJob);
        }

        private Promise<Build> queryJenkinsBuildInformationIncludingUpstreamBuild(BuildReference buildReferenceLevel0) {
            return queryJenkinsBuildInformation(buildReferenceLevel0)
                .flatMap(buildInfoLevel0 ->
                    ParallelBatch.of(
                        buildInfoLevel0.upstreamBuildReferences.stream()
                            .map(buildReferenceLevel1 -> queryJenkinsBuildInformation(buildReferenceLevel1)
                                .flatMap(buildInfoLevel1 ->
                                    ParallelBatch.of(
                                        buildInfoLevel1.upstreamBuildReferences.stream()
                                            .map(this::queryJenkinsBuildInformation)
                                            .collect(toList())
                                    )
                                        .yield()
                                        .map(buildInfoLevel1::withUpstreamBuilds)
                                ))
                            .collect(toList())
                    )
                        .yield()
                        .map(buildInfoLevel0::withUpstreamBuilds)
                );
        }

        private Promise<Build> queryJenkinsBuildInformation(BuildReference buildReference) {
            return httpGet(host + buildReference.jobPath + buildReference.number + JENKINS_API_SUFFIX)
                .map(this::getTextFromResponseBody)
                .map(text -> parseBuildInfo(text, buildReference));
        }

        private Promise<TestReport> queryCucumberReport(BuildReference buildReference) {
            return httpGet(host + buildReference.jobPath + buildReference.number + CUCUMBER_REPORTS_OVERVIEW_PAGE)
                .map(this::getTextFromResponseBody)
                .map(this::repairHtml)
                .map(text -> parseTestReport(text, buildReference));
        }

        private Promise<ReceivedResponse> httpGet(String url) {
            return httpClient.get(URI.create(url))
                .map(result -> {
                    logger.info(String.format("Successful GET for %s", url));
                    return result;
                })
                .onError(throwable ->
                    logger.error(String.format("Error on GET for %s", url), throwable)
                );
        }

        private String getTextFromResponseBody(ReceivedResponse receivedResponse) {
            return receivedResponse.getBody().getText();
        }

        private List<BuildReference> parseBuildNumbersFromJob(String text) {
            Document document = readDocument(text);
            XPathFactory xPathFactory = XPathFactory.instance();

            XPathExpression<Element> buildNumberXPath = xPathFactory.compile("//build/number", Filters.element());

            String lastSuccessfulBuild = getSingleValue("//lastSuccessfulBuild/number", xPathFactory, document).get();
            String firstBuild = getSingleValue("//firstBuild/number", xPathFactory, document).get();

            return buildNumberXPath.evaluate(document).stream()
                .map(Element::getText)
                .filter(number -> !number.equals(firstBuild))
                .filter(number -> !number.equals(lastSuccessfulBuild))
                .map(number -> new BuildReference(number, jenkinsJob))
                .collect(toList());
        }

        private Build parseBuildInfo(String text, BuildReference buildReference) {
            try {
                if (text.contains("Not Found")) return new NullBuild(buildReference);

                return parseBuildInfo(readDocument(text), buildReference);

            } catch (RuntimeException e) {
                throw new RuntimeException(buildReference + ": " + left(text, 200), e);
            }
        }

        Build parseBuildInfo(Document document, BuildReference buildReference) {
            XPathFactory xPathFactory = XPathFactory.instance();

            Duration duration = getSingleValue("//duration", xPathFactory, document)
                .map(Long::parseLong).map(Duration::new).get();

            DateTime startedAt = getSingleValue("//timestamp", xPathFactory, document)
                .map(Long::parseLong).map(DateTime::new).get();

            Optional<String> startedByUser = getSingleValue("//cause/userName", xPathFactory, document)
                .map(this::removeExtSuffix);

            List<BuildReference> upstreamBuilds = parseUpstreamBuilds(xPathFactory, document);

            List<ScmChange> scmChanges = parseScmChanges(xPathFactory, document);

            return new Build(buildReference, duration, startedAt, startedByUser, upstreamBuilds, emptyList(), scmChanges);
        }

        private List<BuildReference> parseUpstreamBuilds(XPathFactory xPathFactory, Document document) {
            XPathExpression<Element> upstreamBuildsXPath = xPathFactory.compile("//cause[@_class=\"hudson.model.Cause$UpstreamCause\"]", Filters.element());

            return upstreamBuildsXPath.evaluate(document).stream()
                .map(element -> new BuildReference(
                    element.getChildText("upstreamBuild"),
                    element.getChildText("upstreamUrl")
                ))
                .collect(toList());
        }

        private List<ScmChange> parseScmChanges(XPathFactory xPathFactory, Document document) {
            XPathExpression<Element> scmChangesXPath = xPathFactory.compile("//changeSet/item", Filters.element());

            return scmChangesXPath.evaluate(document).stream()
                .map(element -> new ScmChange(
                    element.getChildText("commitId"),
                    removeExtSuffix(element.getChild("author").getChildText("fullName")),
                    element.getChildText("comment")
                ))
                .collect(toList());
        }

        private String removeExtSuffix(String name) {
            return StringUtils.removeEnd(name, " (ext)");
        }

        private Optional<String> getSingleValue(String query, XPathFactory xPathFactory, Document document) {
            XPathExpression<Element> xPathExpression = xPathFactory.compile(query, Filters.element());
            List<Element> elements = xPathExpression.evaluate(document);
            if (elements.isEmpty()) return empty();
            return Optional.of(elements.get(0).getText());
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

        private TestReport parseTestReport(String text, BuildReference buildReference) {
            try {
                Document document = readDocument(text);
                XPathFactory xPathFactory = XPathFactory.instance();

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
                throw new RuntimeException(buildReference + ": " + left(text, 200), e);
            }
        }

        private TestReportLine mapHtmlRowToTestReportLine(Element element) {
            return new TestReportLine(
                new Feature(
                    element.getChildren().get(0).getChildren().get(0).getText(),
                    element.getChildren().get(0).getChildren().get(0).getAttributeValue("href")
                ),
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

        private void renderTestReports(List<? extends Pair<Build, TestReport>> pairs) {
            context.getResponse().status(Status.OK).contentType(MediaType.TEXT_HTML);

            List<TestReport> testReports = pairs.stream().map(Pair::getRight).collect(toList());

            List<AggregatedTestReportLine> aggregatedTestReportLines = testReports.stream()
                .flatMap(TestReport::getAllFeatures)
                .distinct()
                .map(feature -> createAggregatedTestReportLine(testReports, feature))
                .sorted(comparing(AggregatedTestReportLine::getFeature))
                .collect(toList());

            context.render(aggregatedReportBuilder.buildHtml(pairs, aggregatedTestReportLines));
        }

        private AggregatedTestReportLine createAggregatedTestReportLine(
            List<? extends TestReport> testReports,
            Feature feature
        ) {
            List<Pair<TestReportLine, TestReport>> allTestReportLinesForThisFeature = testReports.stream()
                .map(testReport -> Pair.of(
                    testReport.getTestReportLineByFeature(feature),
                    testReport))
                .sorted(comparing(pair -> pair.getRight().buildNumber))
                .collect(toList());

            return new AggregatedTestReportLine(feature, allTestReportLinesForThisFeature);
        }
    }

    @Value
    public static class AggregatedReportBuilder {
        String host;
        String jenkinsJob;
        Optional<String> scmRepositoryBaseUrl;
        StringBuilder response = new StringBuilder();

        private String buildHtml(
            List<? extends Pair<Build, TestReport>> pairs,
            List<AggregatedTestReportLine> aggregatedTestReportLines
        ) {
            appendLine("<!DOCTYPE html>");
            appendLine("<html>");
            appendLine("<head>");
            appendLine("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />");
            appendLine("<script type=\"text/javascript\" src=\"js/jquery.min.js\"></script>");
            appendLine("<script type=\"text/javascript\" src=\"js/bootstrap.min.js\"></script>");
            appendLine("<link rel=\"stylesheet\" href=\"css/bootstrap.min.css\" type=\"text/css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/reporting.css\" type=\"text/css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/font-awesome.min.css\"/>");
            appendLine("<link rel=\"stylesheet\" href=\"css/progressbar.css\"/>");
            appendLine("<script>");
            appendLine("function toggleSystemFailures() {");
            appendLine("	var button = document.getElementById('toggle-system-failures-button');");
            appendLine("	");
            appendLine("	if (button.innerText === 'Hide System Failures') {");
            appendLine("		button.innerText = 'Show System Failures';");
            appendLine("		setDisplayForSystemFailureCellsTo('none');");
            appendLine("	} else {");
            appendLine("		button.innerText = 'Hide System Failures';");
            appendLine("		setDisplayForSystemFailureCellsTo('');");
            appendLine("	}");
            appendLine("}");
            appendLine("");
            appendLine("function setDisplayForSystemFailureCellsTo(value) {");
            appendLine("	var systemFailureCells = document.getElementsByClassName('system-failure');");
            appendLine("	");
            appendLine("	for (var i = 0; i < systemFailureCells.length; i ++) {");
            appendLine("		systemFailureCells[i].style.display = value;");
            appendLine("	}");
            appendLine("}");
            appendLine("</script>");
            appendLine("</head>");
            appendLine("<body>");
            appendLine("<table class=\"stats-table table-hover\">");
            appendLine("<thead>");
            appendLine("<tr class=\"header dont-sort\">");
            appendLine(
                "<th>Feature <button id=\"toggle-system-failures-button\" type=\"button\" class=\"btn btn-default\" onclick=\"toggleSystemFailures()\">Hide System Failures</button></th>");
            pairs.stream()
                .sorted(comparing(pair -> pair.getRight().buildNumber))
                .forEach(this::writeOneColumnHeader);
            appendLine("</tr>");
            appendLine("</thead>");

            aggregatedTestReportLines.forEach(aggregatedTestReportLine -> {
                appendLine("<tr>");
                append("<td class=\"tagname\">").append(aggregatedTestReportLine.feature.name).appendLine("</td>");
                aggregatedTestReportLine
                    .testReportLinesAndTestReport
                    .forEach(this::writeOneTestResult);
                appendLine("</tr>");
            });
            appendLine("</table>");
            appendLine("<script>");
            appendLine("$(function () {");
            appendLine("  $('[data-toggle=\"popover\"]').popover()");
            appendLine("})");
            appendLine("</script>");
//            appendLine("<table class='table table-condensed'>");
//            appendLine("<tr><td>Test Text</td><td>Viel längerer Testtext</td></tr>");
//            appendLine("<tr><td>Test Text</td><td>Viel längerer Testtext</td></tr>");
//            appendLine("</table>");
            appendLine("</body>");
            appendLine("</html>");

            return response.toString();
        }

        private void writeOneColumnHeader(Pair<Build, TestReport> pair) {
            TestReport testReport = pair.getRight();
            Build build = pair.getLeft();

            try {
                append("<th style=\"vertical-align: top;\"");
                if (testReport.isSystemFailure()) {
                    append(" class=\"system-failure\"");
                }
                appendLine(">");
                writeBuildLink(jenkinsJob, testReport.buildNumber);

                append(build.getDurationFormatted());
                append("<br/>");
                append(build.getStartedAtDateFormatted());
                append("<br/>");
                appendLine(build.getStartedAtTimeFormatted());
                append("<br/>");
                build.startedByUser.ifPresent(startedByUser -> appendPopover("User", startedByUser));
                if (!build.scmChanges.isEmpty()) {
                    appendPopover("E2E", scmChangesHtml(build));
                    append("<br/>");
                }
                if (build.upstreamBuilds.stream()
                    .flatMap(upstreamBuild -> upstreamBuild.upstreamBuildReferences.stream())
                    .count() > 0) {
                    appendPopover("Project", upstreamBuildsHtml(build));
                    append("<br/>");
                }
                build.upstreamBuilds.stream()
                    .map(Build::getStartedByUser)
                    .flatMap(optionalToStream())
                    .forEach(startedByUser -> appendPopover("User", startedByUser));

                appendLine("</th>");
            } catch (Exception e) {
                throw new RuntimeException("Exception while writing header cell for build " + build.buildReference.number, e);
            }
        }

        private void writeBuildLink(String jobUrl, String buildNumber) {
            append("<a href=\"").append(host).append(jobUrl).append(buildNumber).append("/\">");
            append(buildNumber);
            append("</a>");
            appendLine("<br/>");
        }

        private String buildLink(BuildReference ref) {
            return "<a href='" + host + ref.jobPath + ref.number + "/'>" + ref.number + "</a>";
        }

        private String scmChangesHtml(Build build) {
            return "<table class='table table-condensed'>" +
                build.scmChanges.stream()
                    .map(scmChange -> "<tr>" +
                        scmCommitLink(scmChange, build) +
                        "<td>" + scmChange.user + "</td>" +
                        "<td><p class=&quot;text-left&quot;>" + formatTextAsQuotedHtml(scmChange.comment) + "</p></td>" +
                        "</tr>")
                    .collect(joining("\n")) +
                "</table>";
        }

        private String scmCommitLink(ScmChange scmChange, Build build) {
            return scmRepositoryBaseUrl
                .map(url -> "<td><a href='" + url + build.buildReference.getPlainJobName() +
                    "/commits/" + scmChange.commitId + "'>SCM</a></td>")
                .orElse("");
        }

        private String upstreamBuildsHtml(Build build) {
            return "<table class='table table-condensed'>" +
                build.upstreamBuilds.stream()
                    .flatMap(upstreamBuild -> upstreamBuild.upstreamBuilds.stream())
                    .map(upstreamBuild -> "<tr>" +
                        "<td><p class=&quot;text-left&quot;>" + upstreamBuild.buildReference.getPlainJobName() + "</p></td>" +
                        "<td>" + buildLink(upstreamBuild.buildReference) + "</td>" +
                        "<td>" + scmChangesHtml(upstreamBuild) + "</td>" +
                        "</tr>")
                    .collect(joining("\n")) +
                "</table>";
        }

        private String formatTextAsQuotedHtml(String text) {
            // Replace first line break, as this is the heading of the commit comment
            text = StringUtils.replace(text, "\n", "<br/>", 1);

            text = StringUtils.replace(text, "\"", "&quot;");

            // Replace line breaks followed by a * as sign of a list item
            text = StringUtils.replace(text, "\n*", "<br/>*");

            return text;
        }

        private void appendPopover(String title, String content) {
            append("<a ");
            append("tabindex=\"0\" ");
            append("role=\"button\" ");
            append("data-toggle=\"popover\" ");
            append("data-html=\"true\" ");
            append("data-placement=\"left\" ");
            append("data-content=\"").append(content).append("\">");
            append(title);
            append("</a>");
        }

        private void writeOneTestResult(Pair<TestReportLine, TestReport> testReportLineAndTestReport) {
            String buildNumber = testReportLineAndTestReport.getRight().buildNumber;
            boolean isSystemFailure = testReportLineAndTestReport.getRight().isSystemFailure();
            String featureLink = testReportLineAndTestReport.getLeft().feature.link;
            String status = testReportLineAndTestReport.getLeft().status;
            int failedAndSkippedSteps = testReportLineAndTestReport.getLeft().getFailedAndSkippedStepsInt();
            int totalSteps = testReportLineAndTestReport.getLeft().getTotalStepsInt();

            append("<td class=\"");
            append(status.toLowerCase());
            if (isSystemFailure) {
                append(" system-failure");
            }
            append("\">");
            if (status.equals("Failed")) {
                append("<a href=\"");
                append(host).append(jenkinsJob).append(buildNumber).append(CUCUMBER_REPORTS_PATH).append(featureLink);
                append("\">");
                append("<span class=\"text-danger\">");
                append(failedAndSkippedSteps);
                append(" / ");
                append(totalSteps);
                append("</span>");
                append("</a>");
            } else if (status.equals("Passed")) {
                append("<a href=\"");
                append(host).append(jenkinsJob).append(buildNumber).append(CUCUMBER_REPORTS_PATH).append(featureLink);
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

    private static <T> Function<Optional<T>, Stream<T>> optionalToStream() {
        return (optional) -> {
            if (!optional.isPresent()) return Stream.empty();
            return Stream.of(optional.get());
        };
    }
}