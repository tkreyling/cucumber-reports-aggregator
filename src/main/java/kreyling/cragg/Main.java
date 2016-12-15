package kreyling.cragg;

import ratpack.server.RatpackServer;

public class Main {
    public static void main(String... args) throws Exception {
        RatpackServer.start(server -> server
            .handlers(chain -> chain
                .get(ctx -> ctx.render("Hello " + ctx.getRequest().getQueryParams().get("name") + "!"))
            )
        );
    }
}