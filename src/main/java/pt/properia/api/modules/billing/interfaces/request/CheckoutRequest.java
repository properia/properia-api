package pt.properia.api.modules.billing.interfaces.request;

public record CheckoutRequest(String targetPlanCode, String billingCycle, String returnUrl) {}
