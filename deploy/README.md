# AndesStay - Infraestructura AWS

Responsable: Cristian
Área: Nube y frontend

## Trabajo realizado
- Creación de instancia AWS EC2 con Ubuntu.
- Instalación de Docker y Docker Compose.
- Configuración de acceso SSH a GitHub.
- Descarga de los tres microservicios.
- Construcción de las tres imágenes Docker.
- Configuración de Docker Compose.
- Restricción del acceso SSH.
- Creación de la API HTTP andesstay-api.
- Creación de archivo privado de variables de entorno.

## Microservicios
- BFF: puerto 8080.
- Reservas: puerto 8081.
- Catálogo: puerto 8082.

## Pendiente
- Recibir configuración de Oracle.
- Recibir identificadores de Microsoft Entra.
- Completar JWT Authorizer y CORS.
- Configurar integración privada con EC2.
- Iniciar servicios y ejecutar pruebas.
- Desarrollar frontend React.

## Seguridad
El archivo .env, las contraseñas y las
llaves privadas no deben subirse a GitHub.

Los microservicios están en repositorios separados.
