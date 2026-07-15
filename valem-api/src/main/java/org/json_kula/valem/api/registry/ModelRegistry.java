package org.json_kula.valem.api.registry;

import org.springframework.stereotype.Component;

/**
 * Spring-managed registry bean. Delegates all logic to the service-layer implementation.
 *
 * @see org.json_kula.valem.service.ModelRegistry
 */
@Component
public class ModelRegistry extends org.json_kula.valem.service.ModelRegistry {}
