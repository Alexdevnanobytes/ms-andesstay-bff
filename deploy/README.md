# AndesStay - Despliegue en AWS

Levanta el backend completo (BFF + reservations + catalog) con Docker Compose en una instancia EC2. El único punto público es el API Gateway (`../infra/aws-api-gateway.yaml`); la base de datos es PostgreSQL en Amazon RDS.

```
Frontend React ──> API Gateway (JWT Authorizer) ──> EC2 :8080 BFF ──> reservations :8081 ──> RDS PostgreSQL
                                                                  └──> catalog :8082 ──────┘
```

## Infraestructura actual

| Recurso | Valor |
|---|---|
| EC2 | `andesstay-ec2`, t3.medium, Ubuntu (usuario SSH `ubuntu`), IP elástica `52.1.104.247` |
| Puertos | BFF 8080 (el único publicado), reservations 8081 y catalog 8082 (solo red interna de Docker) |
| Security group del EC2 | SSH 22 restringido; 8080 abierto para el API Gateway |
| Base de datos | RDS PostgreSQL `andesstay`; su security group acepta al security group del EC2 |
| API Gateway | HTTP API `andesstay-api`: rutas `/api/{proxy+}` (Entra ID) y `/cognito/api/{proxy+}` (Cognito) |

## Levantar el backend

1. Clonar los tres repositorios en la misma carpeta (el compose usa rutas relativas):

   ```bash
   mkdir -p ~/AndesStay && cd ~/AndesStay
   git clone https://github.com/Alexdevnanobytes/ms-andesstay-bff.git
   git clone https://github.com/Alexdevnanobytes/ms-andesstay-reservations.git
   git clone https://github.com/Alexdevnanobytes/ms-andesstay-catalog.git
   ```

2. Crear `ms-andesstay-bff/deploy/.env` a partir de `.env.example` y completar los valores. Ese archivo **nunca se sube al repositorio**.

   | Variable | Descripción |
   |---|---|
   | `ENTRA_TENANT_ID`, `ENTRA_API_CLIENT_ID` | Tenant y app `andesstay-api` de Entra ID |
   | `COGNITO_USER_POOL_ID`, `COGNITO_CLIENT_ID` | User Pool y app client de Cognito (opcional) |
   | `INTERNAL_TOKEN` | Clave larga y aleatoria compartida entre los tres servicios |
   | `DB_URL` | `jdbc:postgresql://<endpoint-rds>:5432/andesstay` |
   | `CATALOG_DB_USER`, `CATALOG_DB_PASSWORD` | Credenciales de la base para catalog |
   | `RESERVATIONS_DB_USER`, `RESERVATIONS_DB_PASSWORD` | Credenciales de la base para reservations |
   | `FRONTEND_ORIGIN` | Origen del frontend para CORS (`http://localhost:4200`) |

3. Construir y levantar:

   ```bash
   cd ~/AndesStay/ms-andesstay-bff/deploy
   docker compose up -d --build
   docker compose ps
   ```

4. Verificar (sin token, el BFF debe responder **401**):

   ```bash
   curl -i http://localhost:8080/api/me
   ```

Los contenedores usan `restart: unless-stopped`: vuelven a levantarse solos al reiniciar la instancia (por ejemplo, al iniciar de nuevo el Learner Lab).

## Actualizar a una nueva versión

```bash
cd ~/AndesStay
for r in ms-andesstay-bff ms-andesstay-reservations ms-andesstay-catalog; do git -C $r pull; done
cd ms-andesstay-bff/deploy && docker compose up -d --build
```

## Problemas comunes

| Síntoma | Causa probable |
|---|---|
| API Gateway responde **503** | El BFF no está corriendo o la integración apunta a otra IP |
| `/api/reservations` o `/api/catalog/...` responden **503** con token válido | reservations o catalog no alcanzan la base: revisar el security group de RDS y `docker compose logs` |
| Contenedor reiniciándose en bucle | Falta una variable obligatoria en `.env` (`docker compose logs <servicio>`) |
