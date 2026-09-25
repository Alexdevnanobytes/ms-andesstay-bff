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

## Endpoints

Todas las rutas exigen `Authorization: Bearer <access_token>`. A través del API Gateway se consumen como `/api/...` (Entra ID) o `/cognito/api/...` (Cognito).

| Método | Ruta | Roles | Descripción |
|---|---|---|---|
| GET | `/api/me` | Todos | Perfil del usuario: `id`, `name` y `roles` |
| GET | `/api/dashboard` | Todos | Resumen: total de reservas, ocupadas, llegadas y salidas de hoy. El personal ve todas las reservas; el huésped solo las suyas |
| GET | `/api/reservations` | Todos | Personal: todas las reservas. Huésped: solo las suyas |
| GET | `/api/reservations/{id}` | Todos | Un huésped solo puede ver sus propias reservas (**403** si es de otro) |
| POST | `/api/reservations` | Todos | Crea una reserva (`unitId`, `checkIn`, `checkOut`). El personal puede indicar `guestId`; si va vacío, o si la crea un huésped, queda a nombre de quien la registra |
| PUT | `/api/reservations/{id}` | Todos | Edita unidad y fechas (solo en estado `CREADA`) |
| DELETE | `/api/reservations/{id}` | Todos | Elimina una reserva `CREADA` o `CANCELADA` |
| PATCH | `/api/reservations/{id}/status` | ADMIN, RECEPCIONISTA | Cambia el estado (`{ "status": "CONFIRMADA" }`). Una transición inválida responde **409** |
| GET | `/api/catalog/available?from=&to=` | Todos | Unidades libres en ese período |
| GET | `/api/catalog/units` | ADMIN, RECEPCIONISTA | Lista de unidades |
| GET | `/api/catalog/units/{id}` | ADMIN, RECEPCIONISTA | Una unidad |
| GET | `/api/catalog/units/{id}/availability?from=&to=` | ADMIN, RECEPCIONISTA | Disponibilidad de una unidad |
| POST | `/api/catalog/units` | ADMIN | Crea una unidad (`code`, `type`, `description`, `nightlyRate`) |
| PUT | `/api/catalog/units/{id}` | ADMIN | Edita una unidad |
| DELETE | `/api/catalog/units/{id}` | ADMIN | Desactiva una unidad (**409** si tiene reservas activas) |

En las rutas de reservas que no exigen rol de personal, el BFF igual verifica que un huésped solo opere sobre reservas propias. Los errores de negocio de los microservicios (400, 404, 409) se devuelven tal cual; si un microservicio no responde, el BFF responde **503**.

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

El backend está desplegado mediante Docker Compose en una instancia AWS EC2. La guía de despliegue está en `deploy/README.md`.

El API Gateway (HTTP API `andesstay-api`) es el único punto público del backend. Su definición está en `infra/aws-api-gateway.yaml` (CloudFormation):

- `ANY /api/{proxy+}`: JWT Authorizer de Microsoft Entra ID (scope `access_as_user`)
- `ANY /cognito/api/{proxy+}`: JWT Authorizer de Amazon Cognito (scope `andesstay-api/access_as_user`)
- Ambas rutas usan una integración `HTTP_PROXY` al BFF en el EC2 y CORS para `http://localhost:4200`

Se usa integración HTTP directa porque AWS Academy Learner Lab no permite VPC Link ni ALB interno.

La instancia utiliza una Elastic IP para mantener una dirección pública estable.

Catalog y Reservations utilizan PostgreSQL alojado en AWS RDS.
