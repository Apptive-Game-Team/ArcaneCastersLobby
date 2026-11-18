package com.wordonline.matching.matching.service

/** 지원하지 않는 프로토콜은 기존 시작 절차를 사용한다. */
fun supportedStartProtocol(version: Int): Int = if (version == 1) 1 else 0
