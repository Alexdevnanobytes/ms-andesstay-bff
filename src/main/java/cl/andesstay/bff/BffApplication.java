// Punto de entrada del BFF (Backend For Frontend) de AndesStay.
// El BFF es la única puerta del frontend hacia los microservicios: valida el JWT del usuario,
// aplica los permisos por rol y llama a reservations y catalog. No se conecta a la base de datos.
package cl.andesstay.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication activa la configuración automática de Spring Boot y el escaneo de
// componentes de este paquete (controladores, servicios y beans de configuración como SecurityConfig).
@SpringBootApplication
public class BffApplication {
    // main arranca el servidor web embebido (Tomcat) en el puerto definido en application.yml (8080).
    public static void main(String[] args) { SpringApplication.run(BffApplication.class, args); }
}
