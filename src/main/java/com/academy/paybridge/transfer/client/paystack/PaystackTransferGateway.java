package com.academy.paybridge.transfer.client.paystack;

import com.academy.paybridge.transfer.client.BankInfo;
import com.academy.paybridge.transfer.client.GatewayConfigurationException;
import com.academy.paybridge.transfer.client.GatewayRejectedException;
import com.academy.paybridge.transfer.client.GatewayTransferRequest;
import com.academy.paybridge.transfer.client.GatewayTransferResult;
import com.academy.paybridge.transfer.client.GatewayUnavailableException;
import com.academy.paybridge.transfer.client.ResolvedAccount;
import com.academy.paybridge.transfer.client.TransferGateway;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.BankData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.Envelope;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.RecipientBody;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.RecipientData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.ResolveData;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.TransferBody;
import com.academy.paybridge.transfer.client.paystack.PaystackDtos.TransferData;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.function.Supplier;

/**
 * The Paystack implementation of {@link TransferGateway}. All Paystack specific knowledge
 * (URLs, field names, the recipient step, status words) lives in this package.
 *
 * <p>Paystack flow: (1) resolve the account name, (2) create a transfer recipient,
 * (3) initiate the transfer, (4) read the status or wait for the webhook.
 * Steps 2 and 3 are hidden inside {@link #send}, because other providers do not have a
 * recipient step and the service should not have to care.
 */
@Component
@ConditionalOnProperty(name = "paybridge.transfer.gateway", havingValue = "paystack", matchIfMissing = true)
public class PaystackTransferGateway implements TransferGateway {

    private static final ParameterizedTypeReference<Envelope<ResolveData>> RESOLVE_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<RecipientData>> RECIPIENT_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<TransferData>> TRANSFER_TYPE = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Envelope<List<BankData>>> BANKS_TYPE = new ParameterizedTypeReference<>() { };
    private static final int MAX_MESSAGE_LENGTH = 200;

    private final RestClient restClient;
    private final PaystackProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public PaystackTransferGateway(RestClient paystackRestClient, PaystackProperties properties) {
        this.restClient = paystackRestClient;
        this.properties = properties;
    }

    @Override
    public ResolvedAccount resolveAccount(String accountNumber, String bankCode) {
        String auth = bearer();
        Envelope<ResolveData> response = execute(() -> restClient.get()
                .uri(uri -> uri.path("/bank/resolve")
                        .queryParam("account_number", accountNumber)
                        .queryParam("bank_code", bankCode)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve()
                .body(RESOLVE_TYPE));
        ResolveData data = requireData(response);
        return new ResolvedAccount(accountNumber, data.accountName());
    }

    @Override
    public GatewayTransferResult send(GatewayTransferRequest request) {
        String auth = bearer();
        String recipientCode = createRecipient(request, auth);

        TransferBody body = new TransferBody(
                "balance",
                request.amount().minorUnits(),
                recipientCode,
                request.reference(),
                request.reason(),
                request.amount().currency().getCurrencyCode());
        Envelope<TransferData> response = execute(() -> restClient.post()
                .uri("/transfer")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(TRANSFER_TYPE));
        TransferData data = requireData(response);
        return new GatewayTransferResult(PaystackStatusMapper.map(data.status()), data.transferCode(), response.message());
    }

    @Override
    public GatewayTransferResult fetch(String reference) {
        String auth = bearer();
        Envelope<TransferData> response = execute(() -> restClient.get()
                .uri("/transfer/verify/{reference}", reference)
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve()
                .body(TRANSFER_TYPE));
        TransferData data = requireData(response);
        return new GatewayTransferResult(PaystackStatusMapper.map(data.status()), data.transferCode(), response.message());
    }

    @Override
    public List<BankInfo> listBanks() {
        String auth = bearer();
        Envelope<List<BankData>> response = execute(() -> restClient.get()
                .uri(uri -> uri.path("/bank")
                        .queryParam("country", "nigeria")
                        .queryParam("perPage", 200)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, auth)
                .retrieve()
                .body(BANKS_TYPE));
        return requireData(response).stream()
                .filter(b -> b.name() != null && b.code() != null)
                .map(b -> new BankInfo(b.name(), b.code()))
                .toList();
    }

    private String createRecipient(GatewayTransferRequest request, String auth) {
        RecipientBody body = new RecipientBody(
                "nuban",
                request.accountName() == null || request.accountName().isBlank() ? "PayBridge Recipient" : request.accountName(),
                request.accountNumber(),
                request.bankCode(),
                request.amount().currency().getCurrencyCode());
        Envelope<RecipientData> response = execute(() -> restClient.post()
                .uri("/transferrecipient")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(RECIPIENT_TYPE));
        RecipientData data = requireData(response);
        if (data.recipientCode() == null || data.recipientCode().isBlank()) {
            throw new GatewayUnavailableException("Unexpected response from payment provider");
        }
        return data.recipientCode();
    }

    private String bearer() {
        return "Bearer " + properties.requireTestKey();
    }

    /**
     * Runs one HTTP call and converts every failure into one of our three gateway exceptions.
     * Raw provider errors, URLs and headers (which contain the key) never escape this method.
     */
    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException e) {
            int status = e.getStatusCode().value();
            if (status == 401 || status == 403) {
                throw new GatewayConfigurationException("Payment provider rejected the configured credentials");
            }
            if (status == 429) {
                throw new GatewayUnavailableException("Payment provider is rate limiting requests, try again shortly");
            }
            throw new GatewayRejectedException(providerMessage(e.getResponseBodyAsString()));
        } catch (HttpServerErrorException | ResourceAccessException e) {
            throw new GatewayUnavailableException("Payment provider is unavailable or timed out");
        } catch (RestClientException e) {
            throw new GatewayUnavailableException("Unexpected response from payment provider");
        }
    }

    private <D> D requireData(Envelope<D> response) {
        if (response == null || response.data() == null) {
            throw new GatewayUnavailableException("Unexpected response from payment provider");
        }
        if (!response.status()) {
            throw new GatewayRejectedException(truncate(response.message()));
        }
        return response.data();
    }

    /** Pulls the human readable "message" out of a Paystack error body, trimmed and without control characters. */
    private String providerMessage(String errorBody) {
        try {
            if (errorBody != null && !errorBody.isBlank()) {
                JsonNode message = jsonMapper.readTree(errorBody).path("message");
                if (message.isString() && !message.stringValue().isBlank()) {
                    return truncate(message.stringValue());
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic message
        }
        return "Payment provider rejected the request";
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "Payment provider rejected the request";
        }
        String clean = text.replaceAll("\\p{Cntrl}", " ").trim();
        return clean.length() <= MAX_MESSAGE_LENGTH ? clean : clean.substring(0, MAX_MESSAGE_LENGTH);
    }
}
