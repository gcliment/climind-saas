package com.climind.saas.quotes;

import com.climind.saas.businesses.PublicBusinesses;
import com.climind.saas.notifications.EmailService;
import com.climind.saas.notifications.MailTemplate;
import com.climind.saas.shared.config.AppProperties;
import com.climind.saas.shared.util.ArMoney;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Aviso por email de cada consulta nueva al negocio, si no lo desactivó. Nunca se avisa por simulaciones
 * anónimas (solo cuando el cliente deja sus datos). Se envía después del commit.
 */
@Component
class LeadNotifier {

    private final EmailService email;
    private final PublicBusinesses businesses;
    private final AppProperties properties;
    private final com.climind.saas.subscriptions.EntitlementService entitlements;

    LeadNotifier(EmailService email, PublicBusinesses businesses, AppProperties properties,
                 com.climind.saas.subscriptions.EntitlementService entitlements) {
        this.email = email;
        this.businesses = businesses;
        this.properties = properties;
        this.entitlements = entitlements;
    }

    void newLead(QuoteRequest lead, int priceScale) {
        if (!entitlements.forTenant(lead.getTenantId()).leadEmailNotifications()) {
            return;
        }
        businesses.newLeadRecipient(lead.getTenantId()).ifPresent(to -> {
            Map<String, Object> vars = new HashMap<>();
            vars.put("customerName", lead.getCustomerName());
            vars.put("customerWhatsapp", com.climind.saas.shared.util.Phones.display(lead.getCustomerWhatsapp()));
            vars.put("customerEmail", lead.getCustomerEmail());
            vars.put("comments", lead.getComments());
            vars.put("code", lead.getCode());
            vars.put("calculatorName", lead.getCalculatorName());
            vars.put("total", ArMoney.format(lead.getTotal(), priceScale));
            vars.put("leadLink", properties.publicUrlWithoutTrailingSlash() + "/app/consultas?id=" + lead.getId());
            email.send(MailTemplate.NEW_LEAD, to, vars, null, lead.getTenantId());
        });
    }
}
