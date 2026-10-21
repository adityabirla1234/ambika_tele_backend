package com.ambikatelecom.rewards.rewards.domain;

public enum RewardState {
    ASSIGNED,   // approved; waiting for the customer
    REVEALED,   // customer scratched the card
    REDEEMED    // shop owner paid it at the counter
}
