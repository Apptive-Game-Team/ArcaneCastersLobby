package com.wordonline.matching.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.web.ReactivePageableHandlerMethodArgumentResolver;
import org.springframework.data.web.ReactiveSortHandlerMethodArgumentResolver;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

@Configuration
public class PageableWebFluxConfig implements WebFluxConfigurer {

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        ReactiveSortHandlerMethodArgumentResolver sortResolver =
                new ReactiveSortHandlerMethodArgumentResolver();
        configurer.addCustomResolver(sortResolver);
        configurer.addCustomResolver(new ReactivePageableHandlerMethodArgumentResolver(sortResolver));
    }
}
