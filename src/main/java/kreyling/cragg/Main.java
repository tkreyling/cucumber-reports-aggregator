package kreyling.cragg;

import ratpack.handling.Context;
import ratpack.http.client.HttpClient;
import ratpack.server.BaseDir;
import ratpack.server.RatpackServer;

import java.net.URI;

public class Main {

    public static final String JENKINS_REPORT = "https://jenkins.easycredit.intern/view/KW-B2B/view/kwb2b/job/kwb2b-tests/1322/cucumber-html-reports/";

    public static void main(String... args) throws Exception {
        RatpackServer.start(server -> server
            .serverConfig(c -> c.baseDir(BaseDir.find()).build())
            .handlers(chain -> chain
                    .files(files -> files.dir("static"))
                    .get(Main::handleRequest)
            )
        );
    }

    private static void handleRequest(Context ctx) {
        HttpClient httpClient = ctx.get(HttpClient.class);
        URI uri = URI.create(JENKINS_REPORT + "feature-overview.html");

        httpClient.get(uri).then(response -> {
            ctx.getResponse().status(response.getStatus()).contentType(response.getBody().getContentType().toString());
            ctx.render(response.getBody().getText());
        });
    }
}