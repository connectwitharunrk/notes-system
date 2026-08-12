/**
 * PURE KOTLIN business core.
 *
 * Models, ports (interfaces) and use cases. There is intentionally NO
 * spring-boot-starter on this classpath - if a use case needs a framework type,
 * the design is wrong and the dependency belongs behind a port instead.
 */
dependencies {
    api(project(":common"))
}
