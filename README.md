# AndesStay - BFF

Backend For Frontend del sistema AndesStay desarrollado con Java 17 y Spring Boot.

El BFF funciona como punto de entrada del backend, valida la identidad del usuario y comunica el frontend con los microservicios de reservas y catálogo.

## Tecnologías

- Java 17
- Spring Boot 3.4
- Spring Security
- OAuth 2.0 Resource Server
- JWT
- Microsoft Entra ID
- Amazon Cognito
- Docker
- Docker Compose
- AWS EC2
- AWS API Gateway

## Puerto

    8080

## Responsabilidades

El BFF:

- recibe las peticiones provenientes del frontend;
- valida los JWT;
- aplica autorización por scopes y roles;
- expone el perfil del usuario autenticado;
- comunica el frontend con Reservations y Catalog;
- utiliza autenticación interna mediante `X-Internal-Token`;
- centraliza el acceso a los microservicios.

## Proveedores de identidad

El BFF permite validar tokens provenientes de:

### Microsoft Entra ID

Se valida:

- firma del JWT;
- issuer;
- audience;
- versión `2.0`;
- scope `access_as_user`;
- roles autorizados.

Variables:

    ENTRA_TENANT_ID=
    ENTRA_API_CLIENT_ID=

### Amazon Cognito

El soporte para Cognito es opcional.

Se valida:

- firma mediante las claves JWKS del User Pool;
- issuer;
- `token_use=access`;
- `client_id`;
- scope;
- grupos de Cognito utilizados como roles.

Variables:

    COGNITO_REGION=us-east-1
    COGNITO_USER_POOL_ID=
    COGNITO_CLIENT_ID=

Si `COGNITO_USER_POOL_ID` o `COGNITO_CLIENT_ID` están vacíos, el BFF funciona únicamente con Microsoft Entra ID.

## Scope

La aplicación normaliza los scopes de ambos proveedores al permiso:

    access_as_user

Microsoft Entra ID entrega el scope mediante el claim `scp`.

Amazon Cognito puede entregar:

    andesstay-api/access_as_user

## Roles

Los roles utilizados por AndesStay son:

- `ADMIN`
- `RECEPCIONISTA`
- `HUESPED`

En Microsoft Entra ID se obtienen desde el claim `roles`.

En Amazon Cognito se obtienen desde `cognito:groups`.

## Variables de entorno

    PORT=8080

    ENTRA_TENANT_ID=
    ENTRA_API_CLIENT_ID=

    COGNITO_REGION=us-east-1
    COGNITO_USER_POOL_ID=
    COGNITO_CLIENT_ID=

    INTERNAL_TOKEN=

    RESERVATIONS_URL=
    CATALOG_URL=

    FRONTEND_ORIGIN=http://localhost:4200

Las credenciales y secretos reales no deben almacenarse en GitHub.

## Comunicación interna

Arquitectura interna:

    BFF :8080
       |
       +--> Reservations :8081
       |
       +--> Catalog :8082

El BFF agrega a las peticiones internas:

    X-Internal-Token

El valor debe coincidir con `INTERNAL_TOKEN` configurado en los microservicios.

## Seguridad

El BFF utiliza sesiones stateless.

Todas las rutas protegidas requieren:

- token válido;
- scope `access_as_user`;
- uno de los roles permitidos.

Los preflight CORS mediante `OPTIONS` están permitidos sin autenticación.

Respuestas habituales:

- `401 Unauthorized`: token ausente o inválido.
- `403 Forbidden`: usuario autenticado sin permisos suficientes.
- `503 Service Unavailable`: microservicio interno no disponible.

## CORS

Origen utilizado durante el desarrollo:

    http://localhost:4200

Métodos permitidos:

    GET
    POST
    PUT
    PATCH
    DELETE
    OPTIONS

Headers permitidos:

    Authorization
    Content-Type

## Docker

La configuración de despliegue se encuentra en:

    deploy/

El archivo `deploy/compose.yaml` ejecuta:

- BFF
- Reservations
- Catalog

Los contenedores están configurados para reiniciarse automáticamente junto con la instancia EC2.

## Ejecución local

Ejecutar pruebas:

    mvn test

Iniciar el BFF:

    mvn spring-boot:run

## Arquitectura general

    React
       |
       v
    Proveedor de identidad
    Entra ID / Cognito
       |
       | Access Token
       v
    AWS API Gateway
       |
       v
    BFF :8080
       |
       +--> Reservations :8081
       |
       +--> Catalog :8082
               |
               v
          AWS RDS PostgreSQL

## Infraestructura

El backend está desplegado mediante Docker Compose en una instancia AWS EC2.

La instancia utiliza una Elastic IP para mantener una dirección pública estable.

Catalog y Reservations utilizan PostgreSQL alojado en AWS RDS.
