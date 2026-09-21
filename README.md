# ms-andesstay-bff

Resource Server Spring Boot Java 17. Ejecuta `mvn test` o `mvn spring-boot:run` con `ENTRA_TENANT_ID`, `ENTRA_API_CLIENT_ID`, `INTERNAL_TOKEN`, `RESERVATIONS_URL`, `CATALOG_URL` y `FRONTEND_ORIGIN`. No conecta a Oracle. Escucha en 8080. Exige token para API propia, scope `access_as_user` y roles `ADMIN`, `RECEPCIONISTA` o `HUESPED`.
