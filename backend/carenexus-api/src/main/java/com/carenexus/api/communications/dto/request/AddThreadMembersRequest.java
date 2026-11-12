package com.carenexus.api.communications.dto.request;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AddThreadMembersRequest {

    private List<String> systemIds;
}
