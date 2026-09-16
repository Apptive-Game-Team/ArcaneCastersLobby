package com.wordonline.matching.global.config.database;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;

import java.util.Arrays;

@Configuration
public class R2dbcConfig extends AbstractR2dbcConfiguration {

    private final ConnectionFactory connectionFactory;

    public R2dbcConfig(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public ConnectionFactory connectionFactory() {
        return connectionFactory;
    }

    @Bean
    @Override
    public R2dbcCustomConversions r2dbcCustomConversions() {
        return new R2dbcCustomConversions(CustomConversions.StoreConversions.NONE,
                Arrays.asList(
                        new UserStatusReadConverter(),
                        new UserStatusWriteConverter(),
                        new DecoTypeReadConverter(),
                        new DecoTypeWriteConverter(),
                        new SessionServerTypeReadConverter(),
                        new SessionServerTypeWriteConverter(),
                        new SessionServerStateReadConverter(),
                        new SessionServerStateWriteConverter(),
                        new ContentStateReadConverter(),
                        new ContentStateWriteConverter(),
                        new JsonToStringReadConverter()
                ));
    }
}
