package pt.properia.api.modules.billing.interfaces.request;

public record CheckoutRequest(String planCode, String billingCycle, String returnUrl) {}
