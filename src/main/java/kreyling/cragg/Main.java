package kreyling.cragg;

import ratpack.exec.Promise;
import ratpack.exec.util.ParallelBatch;
import ratpack.handling.Context;
import ratpack.http.MediaType;
import ratpack.http.Status;
import ratpack.http.client.HttpClient;
import ratpack.http.client.ReceivedResponse;
import ratpack.server.BaseDir;
import ratpack.server.RatpackServer;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
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

    private static class JenkinsRequestProcessor {
        private final Context context;

        public JenkinsRequestProcessor(Context context) {
            this.context = context;
        }

        public void process() {
            HttpClient httpClient = context.get(HttpClient.class);

            ParallelBatch.of(Stream.of(1322, 1321, 1319)
                .map(build -> URI.create(JENKINS_JOB + build + CUCUMBER_REPORT))
                .map(httpClient::get)
                .map(promise -> promise.map(receivedResponse ->  receivedResponse.getBody().getText()))
                .collect(Collectors.toList()))
                .yield()
                .then(this::mapJenkinsResponses);
        }

        private void mapJenkinsResponses(List<? extends String> responses) {
            String response = responses.get(0);
            context.getResponse().status(Status.OK).contentType(MediaType.TEXT_HTML);
            context.render(response);
        }

    }
}