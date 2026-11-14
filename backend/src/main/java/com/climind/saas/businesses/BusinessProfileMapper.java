package com.climind.saas.businesses;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface BusinessProfileMapper {

    String ASSET_URL = "/api/v1/public/assets/";

    @Mapping(target = "industry", expression = "java(profile.getIndustry() == null ? null : profile.getIndustry().name())")
    @Mapping(target = "industryLabel", expression = "java(profile.getIndustry() == null ? null : profile.getIndustry().label())")
    @Mapping(target = "logoUrl", expression = "java(logoUrl(profile.getLogoAssetId()))")
    @Mapping(target = "publicSlug", source = "publicSlug")
    @Mapping(target = "onboardingCompleted", source = "onboardingCompleted")
    BusinessDtos.BusinessProfileResponse toResponse(BusinessProfile profile, String publicSlug,
                                                    boolean onboardingCompleted);

    default String logoUrl(java.util.UUID assetId) {
        return assetId == null ? null : ASSET_URL + assetId;
    }
}
