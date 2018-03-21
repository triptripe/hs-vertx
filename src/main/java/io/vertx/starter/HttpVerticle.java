package io.vertx.starter;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.eventbus.EventBus;
import io.vertx.ext.web.client.WebClient;

public class HttpVerticle extends AbstractVerticle {

  public void start() {
    WebClient client = WebClient.create(vertx);
    EventBus eb = vertx.eventBus();
    eb.consumer("server").handler(tokenUsername -> {
      String[] usernameAndToken=tokenUsername.body().toString().split(" ");
            client.get(443, "trackobot.com", "/profile/history.json")
        //username weathered-felguard-7032
        .addQueryParam("username", usernameAndToken[0])
        //token 8T-M1sTcAhe5Dtqp9Jrq
        .addQueryParam("token", usernameAndToken[1])
        .putHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
        .putHeader("Accept-Encoding", "gzip, deflate, br")
        .putHeader("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.6,en;q=0.4")
        .putHeader("Cache-Control", "max-age=0")
        .putHeader("Connection", "keep-alive")
        .putHeader("DNT", "1")
        .putHeader("Host", "trackobot.com")
        .ssl(true)
        .send(s -> {

          if (s.succeeded())
            System.out.println(s.result().bodyAsString());
          else
            System.out.println("failed " + s);
        });
    });

  }
}
