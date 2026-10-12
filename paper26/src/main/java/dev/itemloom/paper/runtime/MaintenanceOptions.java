package dev.itemloom.paper.runtime;

public record MaintenanceOptions(
        boolean checkOpenedInventory, boolean forceDurabilitySync, String expirationMessage) {}
