package ru.studentsplatform.endpoint.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/")
public class MainController {
	@GetMapping("/")
	String getDivisions() {
		return "I am scary zuul api-gateway";
	}
}
