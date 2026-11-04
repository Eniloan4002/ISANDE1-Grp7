package ph.edu.ici.students;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/students")
@PreAuthorize("hasAnyRole('ADMIN','REGISTRAR')")
public class StudentController {
    private final StudentService service;
    public StudentController(StudentService service){this.service=service;}
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="") String search,
        @RequestParam(required=false) Long campusId,@RequestParam(required=false) Long programId,
        @RequestParam(required=false) String voucherCode,@RequestParam(required=false) Boolean active,
        @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="25") int size){
        return service.list(search,campusId,programId,voucherCode,active,page,size);
    }
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable long id){return service.get(id);}
    @PostMapping public ResponseEntity<Map<String,Object>> create(@Valid @RequestBody StudentInput input){
        Map<String,Object> student=service.create(input);
        return ResponseEntity.created(java.net.URI.create("/api/students/"+student.get("id"))).body(student);
    }
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody StudentInput input){return service.update(id,input);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable long id,@RequestParam int version){service.archive(id,version);}
    @GetMapping("/reference-data") public Map<String,Object> referenceData(){return service.referenceData();}
}
