package com.infeara.sales.config;

import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the bundled UI from the same WAR as the API (ADR-004).
 *
 * <p>The Next.js export is copied into {@code classpath:/static} at build time and the WAR is
 * deployed at the ROOT context, so the export's absolute asset URLs ({@code /_next/...}) and
 * client-side links ({@code /leads/}) resolve unchanged.
 *
 * <p>The export uses {@code trailingSlash: true}, so a page is a directory containing
 * {@code index.html} (e.g. {@code static/engagement/mailbox/index.html}). Tomcat's welcome-file
 * mechanism does not apply to resources served through the DispatcherServlet, therefore
 * {@link DirectoryIndexResolver} maps a directory request to its {@code index.html}. Without it,
 * every deep link except {@code /} would 404 inside the WAR even though it works on the dev server.
 */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Content-hashed build output: safe to cache aggressively.
        registry.addResourceHandler("/_next/**")
                .addResourceLocations("classpath:/static/_next/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .resourceChain(true);

        // Everything else (HTML pages, icons, manifest): revalidate so a redeploy is picked up.
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new DirectoryIndexResolver());
    }

    /** Maps {@code /path/} (or {@code /path}) to {@code /path/index.html} when the latter exists. */
    static final class DirectoryIndexResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource direct = super.getResource(resourcePath, location);
            if (direct != null && !isDirectory(direct)) {
                return direct;
            }
            String prefix = resourcePath.endsWith("/") ? resourcePath : resourcePath + "/";
            Resource index = super.getResource(prefix + "index.html", location);
            if (index != null && index.exists()) {
                return index;
            }
            if (direct != null) {
                log.debug("static_resource_directory_without_index path={}", resourcePath);
            }
            return null;
        }

        private boolean isDirectory(Resource resource) {
            try {
                return resource.getFile().isDirectory();
            } catch (IOException | RuntimeException ex) {
                // Packaged inside a JAR/WAR: a directory is not exposed as a File, and no
                // index.html was found, so treat it as a plain missing resource.
                return false;
            }
        }
    }
}
