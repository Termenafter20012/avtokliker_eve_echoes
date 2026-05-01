import sys
import time

def initialize_systems():
    print("Джарвис: Инициализация систем...")
    time.sleep(1)
    print("Джарвис: Все системы функционируют в штатном режиме.")

if __name__ == "__main__":
    try:
        initialize_systems()
        print("Проект успешно собран. Жду ваших указаний, сэр.")
    except Exception as e:
        print(f"Ошибка при сборке: {e}")