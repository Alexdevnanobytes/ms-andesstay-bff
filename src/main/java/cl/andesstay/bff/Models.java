// Modelos (DTO) que el BFF recibe del frontend o intercambia con los microservicios.
// Se usan "records" de Java: clases inmutables cuyo constructor, getters, equals y toString se generan solos.
// Jackson los convierte automáticamente desde y hacia JSON.
package cl.andesstay.bff;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

// Clase contenedora: agrupa todos los records en un solo archivo. Es final y con constructor privado
// porque nunca se instancia; solo sirve de "carpeta".
public final class Models {

    private Models() { }
    // Unidad del catálogo (habitación o cabaña) tal como la devuelve ms-andesstay-catalog.
    // nightlyRate usa BigDecimal para no perder precisión con dinero.

    public record Unit(String id, String code, String type, String description, BigDecimal nightlyRate, boolean active) { }
    // Cuerpo para crear o editar una unidad (POST/PUT /api/catalog/units).
    // Las anotaciones de jakarta.validation se revisan con @Valid en el controlador:
    // si un campo no cumple, Spring responde 400 Bad Request antes de llamar al microservicio.
    // @DecimalMin("0.01") impide tarifas en cero o negativas.

    public record UnitRequest(@NotBlank String code, @NotBlank String type, @NotBlank String description,
                              @NotNull @DecimalMin("0.01") BigDecimal nightlyRate) { }
    // Reserva tal como la devuelve ms-andesstay-reservations.
    // guestId es el identificador del huésped: el "oid" de Entra ID o el "sub" de Cognito.
    // status es el estado actual (CREADA, CONFIRMADA, CHECKIN_PENDIENTE, EN_ESTADIA, CHECKOUT o CANCELADA).

    public record Reservation(String id, String guestId, String unitId, LocalDate checkIn, LocalDate checkOut,
                              String status, Instant createdAt) { }
    // Cuerpo para crear una reserva (POST /api/reservations).
    // guestId es opcional: solo el personal (ADMIN/RECEPCIONISTA) puede indicarlo; si va vacío,
    // o si quien crea es un huésped, el BFF usa el id del usuario autenticado.

    public record CreateReservation(String guestId, @NotBlank String unitId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut) { }
    // Cuerpo para editar una reserva (PUT /api/reservations/{id}): cambia unidad y fechas, no el huésped.

    public record UpdateReservation(@NotBlank String unitId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut) { }
    // Cuerpo para cambiar el estado (PATCH /api/reservations/{id}/status), por ejemplo {"status":"CONFIRMADA"}.

    public record StatusUpdate(@NotBlank String status) { }
    public record RabbitQueueRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String name) { }
    public record RabbitExchangeRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String name,
                                       @NotBlank @Pattern(regexp = "direct|topic") String type) { }
    public record RabbitBindingRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String queue,
                                       @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String exchange,
                                       @NotBlank @Pattern(regexp = "[A-Za-z0-9._*-]{1,255}") String routingKey) { }
    // Respuesta de GET /api/me: quién es el usuario según su token y qué roles tiene.
    // El frontend la usa para decidir qué menús mostrar.

    public record Profile(String id, String name, List<String> roles) { }
    // Respuesta de GET /api/dashboard: resumen que calcula el propio BFF a partir de las reservas.
    // occupied = reservas EN_ESTADIA; arrivalsToday y departuresToday = llegadas y salidas de hoy.

    public record Dashboard(int totalReservations, long occupied, long arrivalsToday, long departuresToday,
                            List<Reservation> reservations) { }
}
