package cl.andesstay.bff;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class Models {
    private Models() { }
    public record Unit(String id, String code, String type, String description, BigDecimal nightlyRate, boolean active) { }
    public record UnitRequest(@NotBlank String code, @NotBlank String type, @NotBlank String description,
                              @NotNull @DecimalMin("0.01") BigDecimal nightlyRate) { }
    public record Reservation(String id, String guestId, String unitId, LocalDate checkIn, LocalDate checkOut,
                              String status, Instant createdAt) { }
    public record CreateReservation(String guestId, @NotBlank String unitId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut) { }
    public record UpdateReservation(@NotBlank String unitId, @NotNull LocalDate checkIn, @NotNull LocalDate checkOut) { }
    public record StatusUpdate(@NotBlank String status) { }
    public record RabbitQueueRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String name) { }
    public record RabbitExchangeRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String name,
                                       @NotBlank @Pattern(regexp = "direct|topic") String type) { }
    public record RabbitBindingRequest(@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String queue,
                                       @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String exchange,
                                       @NotBlank @Pattern(regexp = "[A-Za-z0-9._*-]{1,255}") String routingKey) { }
    public record Profile(String id, String name, List<String> roles) { }
    public record Dashboard(int totalReservations, long occupied, long arrivalsToday, long departuresToday,
                            List<Reservation> reservations) { }
}
