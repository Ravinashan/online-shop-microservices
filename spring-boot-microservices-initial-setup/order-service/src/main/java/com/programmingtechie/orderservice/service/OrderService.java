package com.programmingtechie.orderservice.service;

import com.programmingtechie.orderservice.dto.InventoryResponse;
import com.programmingtechie.orderservice.dto.OrderLineItemsDto;
import com.programmingtechie.orderservice.dto.OrderRequest;
import com.programmingtechie.orderservice.event.OrderPlacedEvent;
import com.programmingtechie.orderservice.model.Order;
import com.programmingtechie.orderservice.model.OrderLineItems;
import com.programmingtechie.orderservice.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final WebClient.Builder webClientBuilder;
    private final KafkaTemplate<String,OrderPlacedEvent> kafkaTemplate;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());

        List<OrderLineItems> orderLineItems = orderRequest.getOrderLineItemsDtoList()
                .stream()
                .map(this::mapToDto)
                .toList();

        order.setOrderLineItemsList(orderLineItems);
        List<String> skuCodes = order.getOrderLineItemsList().stream()
                .map(OrderLineItems::getSkuCode)
                .toList();

        // call inventory service and place order if product in stock
        InventoryResponse[] inventoryResponses =  webClientBuilder.build().get()
                                    .uri("http://inventory-service/api/inventory", uriBuilder -> uriBuilder.queryParam("skuCodes", String.join(",", skuCodes)).build())
                                    .retrieve()
                                    .bodyToMono(InventoryResponse[].class)
                                    .block();

        assert inventoryResponses != null;
        // Create a map for quick lookup of in-stock status
        Map<String, Boolean> stockMap = Arrays.stream(inventoryResponses)
                .collect(Collectors.toMap(InventoryResponse::getSkuCode, InventoryResponse::isInStock));

        // Check if all requested products are in stock
        boolean allInStock = order.getOrderLineItemsList().stream()
                .allMatch(item -> stockMap.getOrDefault(item.getSkuCode(), false));



        if(Boolean.TRUE.equals(allInStock)){
            orderRepository.save(order);
            kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
            return "Order Placed Successfully";
        }
        else{
            throw new IllegalArgumentException("product is not in stock please try again!");
        }

    }

    private OrderLineItems mapToDto(OrderLineItemsDto orderLineItemsDto) {
        OrderLineItems orderLineItems = new OrderLineItems();
        orderLineItems.setPrice(orderLineItemsDto.getPrice());
        orderLineItems.setQuantity(orderLineItemsDto.getQuantity());
        orderLineItems.setSkuCode(orderLineItemsDto.getSkuCode());
        return orderLineItems;
    }
}
